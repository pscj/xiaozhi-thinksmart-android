package com.justhalfbit.thinksmart;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Base64;
import java.util.ArrayDeque;
import java.util.UUID;
import org.json.JSONObject;

/** BLE transport only. Pairing and credentials remain on the gateway. */
final class MuseBle {
  private static final UUID SERVICE = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c");
  private static final UUID RX = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01");
  private static final UUID TX = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c");
  private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  interface Sender { void send(JSONObject message); }
  private final Context context;
  private final Sender sender;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
  private BluetoothGattServer server;
  private BluetoothLeAdvertiser advertiser;
  private BluetoothGattCharacteristic tx;
  private BluetoothDevice peer;
  private String id = "";
  private long generation;
  private long connectionGeneration;
  private boolean released;
  private long disconnectAt;
  private AdvertiseCallback advertiseCallback;
  private boolean notifying;
  private boolean subscribed;
  private boolean finishPending;
  private String originalName;
  private String advertisedName;
  private final BroadcastReceiver bluetoothState = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent intent) {
      int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
      if ((state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) && !id.isEmpty()) {
        fail();
      } else if (state == BluetoothAdapter.STATE_ON) {
        restoreAdapterName();
      }
    }
  };
  MuseBle(Context context, Sender sender) {
    this.context = context; this.sender = sender;
    IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(bluetoothState, filter, Context.RECEIVER_EXPORTED);
    else context.registerReceiver(bluetoothState, filter);
    restoreAdapterName();
  }
  private void restoreAdapterName() {
    android.content.SharedPreferences saved = context.getSharedPreferences("muse_ble", Context.MODE_PRIVATE);
    String previous = saved.getString("previous_name", null);
    String pairing = saved.getString("pairing_name", null);
    if (previous == null || pairing == null) return;
    try {
      BluetoothManager m = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
      BluetoothAdapter a = m == null ? null : m.getAdapter();
      if (a == null || !a.isEnabled()) return;
      if (!pairing.equals(a.getName()) || a.setName(previous))
        saved.edit().remove("previous_name").remove("pairing_name").apply();
    } catch (SecurityException | IllegalStateException ignored) {}
  }
  void release() {
    handler.post(() -> {
      released = true;
      stopInternal();
      try { context.unregisterReceiver(bluetoothState); } catch (IllegalArgumentException ignored) {}
    });
  }
  private void send(String action, Object... fields) {
    JSONObject o = VoiceService.json("type", "muse_pair", "action", action, "pair_id", id);
    try { for (int i = 0; i < fields.length; i += 2) o.put((String) fields[i], fields[i + 1]); }
    catch (Exception ignored) { return; }
    sender.send(o);
  }
  void handle(JSONObject msg) {
    handler.post(() -> { if (!released) handleInternal(msg); });
  }
  private void handleInternal(JSONObject msg) {
    String action = msg.optString("action");
    if (action.equals("start")) { start(msg.optString("pair_id"), msg.optString("name")); return; }
    if (!id.equals(msg.optString("pair_id")) || id.isEmpty()) return;
    if (action.equals("notify")) {
      try {
        byte[] packet = Base64.decode(msg.optString("data"), Base64.DEFAULT);
        if (queue.size() >= 64) { fail(); return; } queue.add(packet); drain();
      } catch (IllegalArgumentException e) { fail(); }
    } else if (action.equals("disconnect")) {
      requestDisconnect(msg.optLong("delay_ms", 500));
    } else if (action.equals("finished")) { finishPending = true; drain(); }
    else if (action.equals("stop")) stopInternal();
  }
  private void start(String pairId, String name) {
    stopInternal(); id = pairId;
    final long session = generation;
    advertiseCallback = new AdvertiseCallback() {
      @Override public void onStartFailure(int errorCode) { postSession(session, () -> fail()); }
    };
    if (id.isEmpty() || !name.matches("MuseGadget[0-9A-F]{6}")) { fail(); return; }
    try {
      BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
      BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
      if (adapter == null || !adapter.isEnabled() || !adapter.isMultipleAdvertisementSupported()) { fail(); return; }
      advertiser = adapter.getBluetoothLeAdvertiser();
      if (advertiser == null) { fail(); return; }
      originalName = adapter.getName(); advertisedName = name;
      if (originalName != null) context.getSharedPreferences("muse_ble", Context.MODE_PRIVATE).edit()
          .putString("previous_name", originalName).putString("pairing_name", advertisedName).commit();
      if (!adapter.setName(name)) { fail(); return; }
      server = manager.openGattServer(context, createCallback(session));
      if (server == null) { fail(); return; }
      BluetoothGattService service = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
      BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(RX,
          BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
          BluetoothGattCharacteristic.PERMISSION_WRITE);
      tx = new BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY,
          BluetoothGattCharacteristic.PERMISSION_READ);
      tx.addDescriptor(new BluetoothGattDescriptor(CCC, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
      service.addCharacteristic(rx); service.addCharacteristic(tx);
      if (!server.addService(service)) fail();
    } catch (SecurityException | IllegalStateException e) { fail(); }
  }
  private void postSession(long session, Runnable action) {
    handler.post(() -> { if (session == generation && !id.isEmpty() && !released) action.run(); });
  }
  private BluetoothGattServerCallback createCallback(long session) {
    return new BluetoothGattServerCallback() {
    @Override public void onServiceAdded(int status, BluetoothGattService service) {
      postSession(session, () -> {
      if (status != BluetoothGatt.GATT_SUCCESS) { fail(); return; }
      try {
        AdvertiseSettings settings = new AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build();
        AdvertiseData data = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).addManufacturerData(0xffff, new byte[] {0}).build();
        AdvertiseData response = new AdvertiseData.Builder().setIncludeDeviceName(true).build();
        advertiser.startAdvertising(settings, data, response, advertiseCallback);
      } catch (SecurityException | IllegalStateException e) { fail(); }
      });
    }
    @Override public void onConnectionStateChange(BluetoothDevice device, int status, int state) {
      postSession(session, () -> {
      if (state == BluetoothProfile.STATE_CONNECTED) {
        if (peer != null && !peer.equals(device)) { try { server.cancelConnection(device); } catch (SecurityException ignored) {} return; }
        peer = device;
      } else if (peer != null && peer.equals(device)) { peer = null; subscribed = false; connectionGeneration++; disconnectAt = 0; queue.clear(); notifying = false; send("disconnect"); }
      });
    }
    @Override public void onMtuChanged(BluetoothDevice device, int mtu) { postSession(session, () -> { if (device.equals(peer)) send("mtu", "mtu", mtu); }); }
    @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean prepared, boolean responseNeeded, int offset, byte[] value) {
      postSession(session, () -> {
      boolean ok = device.equals(peer) && characteristic.getUuid().equals(RX) && !prepared && offset == 0 && value != null && value.length <= 512;
      try { if (responseNeeded) server.sendResponse(device, requestId, ok ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE, 0, null); } catch (SecurityException ignored) {}
      if (ok) send("write", "data", Base64.encodeToString(value, Base64.NO_WRAP));
      });
    }
    @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean prepared, boolean responseNeeded, int offset, byte[] value) {
      postSession(session, () -> {
      boolean ok = device.equals(peer) && descriptor.getUuid().equals(CCC) && !prepared && offset == 0 && value != null && value.length == 2;
      if (ok) { subscribed = value[0] == 1 && value[1] == 0; drain(); }
      try { if (responseNeeded) server.sendResponse(device, requestId, ok ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE, 0, null); } catch (SecurityException ignored) {}
      });
    }
    @Override public void onNotificationSent(BluetoothDevice device, int status) { postSession(session, () -> { if (!device.equals(peer) || !notifying) return; notifying = false; if (status != BluetoothGatt.GATT_SUCCESS) fail(); else drain(); }); }
  };
  }
  private void requestDisconnect(long delayMs) {
    if (peer == null || server == null) return;
    long delay = Math.max(0, Math.min(delayMs, 5000));
    disconnectAt = SystemClock.uptimeMillis() + delay;
    final long session = generation;
    final long connection = connectionGeneration;
    handler.postDelayed(() -> {
      if (session == generation && connection == connectionGeneration) drain();
    }, delay);
    // A missing notification callback must not hold a failed connection forever.
    handler.postDelayed(() -> {
      if (session == generation && connection == connectionGeneration && disconnectAt != 0)
        disconnectPeer();
    }, delay + 5000);
  }
  private void disconnectPeer() {
    disconnectAt = 0;
    try { if (server != null && peer != null) server.cancelConnection(peer); }
    catch (SecurityException | IllegalStateException e) { fail(); }
  }
  private void drain() {
    if (disconnectAt != 0 && SystemClock.uptimeMillis() >= disconnectAt && !notifying && queue.isEmpty()) {
      disconnectPeer(); return;
    }
    if (finishPending && !notifying && queue.isEmpty()) { stopInternal(); return; }
    if (notifying || !subscribed || peer == null || server == null || queue.isEmpty()) return;
    try { tx.setValue(queue.remove()); notifying = true; if (!server.notifyCharacteristicChanged(peer, tx, false)) fail(); }
    catch (SecurityException | IllegalStateException e) { fail(); }
  }
  private void fail() { send("error"); stopInternal(); }
  void stop() { handler.post(() -> stopInternal()); }
  private void stopInternal() {
    generation++;
    connectionGeneration++;
    disconnectAt = 0;
    try { if (advertiser != null) advertiser.stopAdvertising(advertiseCallback); }
    catch (SecurityException | IllegalStateException ignored) {}
    try { if (server != null) server.close(); }
    catch (SecurityException | IllegalStateException ignored) {}
    restoreAdapterName();
    advertiser = null; server = null; peer = null; tx = null; subscribed = false; notifying = false; finishPending = false;
    originalName = null; advertisedName = null; queue.clear(); id = "";
  }
}

package com.justhalfbit.thinksmart;

import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattServerCallback;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import org.json.JSONObject;

/** Exercises callback ordering without starting the device's BLE advertiser. */
public final class MuseBleRunner extends Instrumentation {
  private MuseBle ble;
  private Throwable failure;
  private final ArrayList<JSONObject> sent = new ArrayList<>();
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  interface Checked { void run() throws Exception; }
  private void main(Checked action) throws Exception {
    runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure = e; } });
    if (failure != null) throw new AssertionError(failure);
  }
  private Object get(String name) throws Exception {
    Field f = MuseBle.class.getDeclaredField(name); f.setAccessible(true); return f.get(ble);
  }
  private void set(String name, Object value) throws Exception {
    Field f = MuseBle.class.getDeclaredField(name); f.setAccessible(true); f.set(ble, value);
  }
  private Object call(String name, Class<?>[] types, Object... args) throws Exception {
    Method m = MuseBle.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(ble, args);
  }
  private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      main(() -> ble = new MuseBle(getTargetContext(), sent::add));
      main(() -> {
        set("id", "old");
        BluetoothGattServerCallback old = (BluetoothGattServerCallback) call("createCallback", new Class<?>[]{long.class}, get("generation"));
        call("stopInternal", new Class<?>[]{});
        // A late success used to dereference a null advertiser here.
        old.onServiceAdded(BluetoothGatt.GATT_SUCCESS, null);
      });
      main(() -> check(sent.isEmpty(), "late success must be ignored"));
      main(() -> {
        set("id", "old");
        BluetoothGattServerCallback old = (BluetoothGattServerCallback) call("createCallback", new Class<?>[]{long.class}, get("generation"));
        // Queue stop before the callback, so validation must happen on execution.
        ble.stop();
        old.onServiceAdded(BluetoothGatt.GATT_FAILURE, null);
      });
      main(() -> { set("id", "new"); check(sent.isEmpty(), "old failure must not send an error"); });
      main(() -> {
        BluetoothGattServerCallback old = (BluetoothGattServerCallback) call("createCallback", new Class<?>[]{long.class}, get("generation"));
        call("stopInternal", new Class<?>[]{});
        set("id", "new");
        old.onServiceAdded(BluetoothGatt.GATT_FAILURE, null);
      });
      main(() -> check("new".equals(get("id")) && sent.isEmpty(), "old failure must not close new session"));
      ble.handle(new JSONObject().put("action", "finished").put("pair_id", "old"));
      main(() -> check("new".equals(get("id")), "old finished must not stop new session"));
      main(() -> {
        set("disconnectAt", SystemClock.uptimeMillis() + 10000);
        call("drain", new Class<?>[]{});
        check((Long) get("disconnectAt") != 0, "must honor SDK delay");
        set("disconnectAt", SystemClock.uptimeMillis() - 1);
        set("notifying", true);
        call("drain", new Class<?>[]{});
        check((Long) get("disconnectAt") != 0, "must wait for notification callback");
        set("notifying", false);
        @SuppressWarnings("unchecked") ArrayDeque<byte[]> queue = (ArrayDeque<byte[]>) get("queue");
        queue.add(new byte[]{1});
        call("drain", new Class<?>[]{});
        check((Long) get("disconnectAt") != 0, "must wait for queued packets");
        queue.clear();
        call("drain", new Class<?>[]{});
        check((Long) get("disconnectAt") == 0, "must disconnect after delay and queue drain");
      });
      result.putString("stream", "Muse BLE callback isolation and disconnect ordering passed\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      result.putString("stream", android.util.Log.getStackTraceString(e));
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      if (ble != null) ble.release();
    }
  }
}

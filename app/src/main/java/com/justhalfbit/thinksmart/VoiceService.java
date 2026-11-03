package com.justhalfbit.thinksmart;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.*;

/** One serial owner for protocol state; hardware audio lives on dedicated threads. */
public class VoiceService extends Service {
  public interface Listener {
    void update(String state, String text, String error);
  }

  public class LocalBinder extends Binder {
    public VoiceService service() {
      return VoiceService.this;
    }
  }

  private final LocalBinder binder = new LocalBinder();
  private HandlerThread worker;
  private Handler io;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final OkHttpClient client =
      new OkHttpClient.Builder()
          .connectTimeout(8, TimeUnit.SECONDS)
          .readTimeout(20, TimeUnit.SECONDS)
          .pingInterval(20, TimeUnit.SECONDS)
          .build();
  private SharedPreferences prefs;
  private AudioEngine audio;
  private WebSocket socket;
  private boolean acceptTurn,
      ready,
      uploading,
      manual,
      muted = true,
      destroyed,
      retry = true,
      wantsListen,
      pttHeld;
  private long listenStarted, lastExtended;
  private long turn, abortUntil;
  private String session = "", pendingText = "", state = "待机", error = "";
  private String threadEntry = "default";
  private StringBuilder transcript = new StringBuilder();
  private volatile Listener listener;
  private PowerManager.WakeLock wakeLock;
  private final Runnable listenTimeout = () -> endInternal();

  public void attach(Listener value) {
    listener = value;
    io.post(this::publish);
  }

  public void detach() {
    listener = null;
  }

  public android.content.SharedPreferences settings() {
    return prefs;
  }

  static JSONObject json(Object... pairs) {
    JSONObject o = new JSONObject();
    try {
      for (int i = 0; i < pairs.length; i += 2) o.put((String) pairs[i], pairs[i + 1]);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return o;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    prefs = getSharedPreferences("config", MODE_PRIVATE);
    threadEntry = prefs.getString("threadEntry", "default");
    transcript.append(prefs.getString("chat:" + threadEntry, ""));
    if (!prefs.contains("device"))
      prefs
          .edit()
          .putString("device", UUID.randomUUID().toString())
          .putString("client", UUID.randomUUID().toString())
          .apply();
    worker = new HandlerThread("voice-state");
    worker.start();
    io = new Handler(worker.getLooper());
    NotificationChannel channel =
        new NotificationChannel("voice", "语音助手", NotificationManager.IMPORTANCE_LOW);
    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
        .createNotificationChannel(channel);
    Intent stop = new Intent(this, VoiceService.class).setAction("stop");
    PendingIntent pi =
        PendingIntent.getService(
            this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    startForeground(
        1,
        new Notification.Builder(this, "voice")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("小麦语音助手运行中")
            .setContentText("本地唤醒已启用；点停止释放麦克风")
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "停止", pi).build())
            .build());
    wakeLock =
        ((PowerManager) getSystemService(POWER_SERVICE))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "thinksmart:voice");
    wakeLock.acquire();
    audio =
        new AudioEngine(
            this,
            new AudioEngine.Callback() {
              public void frame(byte[] frame, boolean speech) {
                io.post(
                    () -> {
                      if (speech && uploading && !manual && state.equals("聆听中")) {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastExtended > 250) {
                          lastExtended = now;
                          io.removeCallbacks(listenTimeout);
                          io.postDelayed(
                              listenTimeout,
                              Math.max(0, Math.min(2500, listenStarted + 60000 - now)));
                        }
                      }
                      if (ready
                          && uploading
                          && socket != null
                          && !((AudioManager) getSystemService(AUDIO_SERVICE)).isMicrophoneMute()) {
                        if (socket.queueSize() > 128 * 1024) {
                          fail("上传积压，连接已重置");
                          return;
                        }
                        socket.send(ByteString.of(frame));
                      }
                    });
              }

              public void wake() {
                io.post(
                    () -> {
                      if (!uploading) requestListen(false);
                    });
              }

              public void error(String text) {
                io.post(
                    () -> {
                      error = text;
                      publish();
                    });
              }
            });
    audio.setWakeEnabled(prefs.getBoolean("wake", true));
    audio.start();
    io.post(this::connect);
  }

  @Override
  public int onStartCommand(Intent i, int flags, int id) {
    if (i != null && "stop".equals(i.getAction())) {
      audio.stop();
      io.post(
          () -> {
            endInternal();
            retry = false;
            if (socket != null) {
              socket.cancel();
              socket = null;
            }
            ready = false;
            setState("服务已停止");
          });
      stopForeground(true);
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  private void publish() {
    String s = state, t = transcript.toString(), e = error;
    Listener l = listener;
    if (l != null)
      main.post(
          () -> {
            if (listener == l) l.update(s, t, e);
          });
  }

  private void setState(String value) {
    state = value;
    publish();
  }

  private void message(String role, String text) {
    transcript.append(role).append("：").append(text).append("\n\n");
    if (transcript.length() > 24000) transcript.delete(0, transcript.length() - 20000);
    prefs.edit().putString("chat:" + threadEntry, transcript.toString()).apply();
    publish();
  }

  private void connect() {
    if (destroyed || socket != null) return;
    String url = prefs.getString("ws", "").trim();
    if (url.isEmpty()) {
      error = "先在设置中填写小智网关 WebSocket 地址";
      publish();
      return;
    }
    try {
      setState("连接中");
      Request req =
          new Request.Builder()
              .url(url)
              .header("Authorization", "Bearer " + prefs.getString("token", ""))
              .header("Protocol-Version", "1")
              .header("Device-Id", prefs.getString("device", ""))
              .header("Client-Id", prefs.getString("client", ""))
              .build();
      socket =
          client.newWebSocket(
              req,
              new WebSocketListener() {
                public void onOpen(WebSocket ws, Response r) {
                  io.post(
                      () -> {
                        if (socket != ws) return;
                        ws.send(
                            json(
                                    "type",
                                    "hello",
                                    "version",
                                    1,
                                    "transport",
                                    "websocket",
                                    "features",
                                    json("mcp", true, "vad_end", true, "ready_on_tts_start", true),
                                    "audio_params",
                                    json(
                                        "format",
                                        "opus",
                                        "sample_rate",
                                        16000,
                                        "channels",
                                        1,
                                        "frame_duration",
                                        20))
                                .toString());
                        io.postDelayed(
                            () -> {
                              if (socket == ws && !ready) fail("网关 hello 超时");
                            },
                            10000);
                      });
                }

                public void onMessage(WebSocket ws, String text) {
                  io.post(
                      () -> {
                        if (socket == ws)
                          try {
                            onJson(new JSONObject(text));
                          } catch (Exception e) {
                            error = "协议消息处理失败：" + e.getMessage();
                            publish();
                          }
                      });
                }

                public void onMessage(WebSocket ws, ByteString b) {
                  io.post(
                      () -> {
                        if (socket == ws && ready && !muted) audio.play(b.toByteArray());
                      });
                }

                public void onFailure(WebSocket ws, Throwable t, Response r) {
                  io.post(
                      () -> {
                        if (socket == ws)
                          fail(
                              "连接失败："
                                  + t.getClass().getSimpleName()
                                  + (r != null ? " HTTP " + r.code() : ""));
                      });
                }

                public void onClosing(WebSocket ws, int code, String reason) {
                  ws.close(code, reason);
                }

                public void onClosed(WebSocket ws, int code, String reason) {
                  io.post(
                      () -> {
                        if (socket == ws) fail("连接已关闭（" + code + "）");
                      });
                }
              });
    } catch (Exception e) {
      fail("地址无效，请填写 ws:// 或 wss:// 地址");
    }
  }

  private void fail(String reason) {
    if (destroyed) return;
    WebSocket old = socket;
    socket = null;
    if (old != null) old.cancel();
    ready = uploading = false;
    acceptTurn = false;
    muted = true;
    turn++;
    abortUntil = 0;
    audio.clear();
    io.removeCallbacks(listenTimeout);
    error = reason;
    setState("未连接");
    if (retry) io.postDelayed(this::connect, 5000);
  }

  private boolean send(JSONObject o) {
    if (!ready || socket == null) return false;
    try {
      o.put("session_id", session);
    } catch (JSONException ignored) {
    }
    return socket.send(o.toString());
  }

  public void wake() {
    io.post(() -> requestListen(false));
  }

  public void ptt(boolean down) {
    io.post(
        () -> {
          pttHeld = down;
          if (down) requestListen(true);
          else if (manual) {
            if (uploading) {
              uploading = false;
              send(json("type", "listen", "state", "stop"));
              setState("识别中");
            } else wantsListen = false;
          }
        });
  }

  private void requestListen(boolean isManual) {
    manual = isManual;
    pendingText = "";
    if (!ready) {
      wantsListen = true;
      connect();
      return;
    }
    if (!muted || state.equals("思考中") || state.equals("识别中") || state.equals("回复中")) {
      send(json("type", "abort", "reason", "wake_word_detected", "played_ms", audio.playedMs()));
      audio.clear();
      muted = true;
      acceptTurn = false;
      uploading = false;
      turn++;
      wantsListen = true;
      abortUntil = SystemClock.elapsedRealtime() + 3000;
      long marker = turn;
      io.postDelayed(
          () -> {
            if (marker == turn && wantsListen) {
              abortUntil = 0;
              beginListen();
            }
          },
          3000);
      return;
    }
    beginListen();
  }

  private void beginListen() {
    if (!ready || (manual && !pttHeld)) {
      wantsListen = false;
      return;
    }
    listenStarted = SystemClock.elapsedRealtime();
    wantsListen = false;
    abortUntil = 0;
    acceptTurn = true;
    muted = true;
    uploading = true;
    turn++;
    send(json("type", "listen", "state", "start", "mode", manual ? "manual" : "realtime"));
    setState("聆听中");
    io.removeCallbacks(listenTimeout);
    io.postDelayed(listenTimeout, manual ? 60000 : 10000);
  }

  public void end() {
    io.post(this::endInternal);
  }

  private void endInternal() {
    turn++;
    acceptTurn = false;
    wantsListen = false;
    pendingText = "";
    abortUntil = 0;
    if (uploading) send(json("type", "listen", "state", "stop"));
    send(json("type", "abort", "played_ms", audio.playedMs()));
    uploading = false;
    muted = true;
    audio.clear();
    io.removeCallbacks(listenTimeout);
    setState("待机");
  }

  public void text(String text) {
    io.post(
        () -> {
          String t = text.trim();
          if (t.isEmpty()) return;
          endInternal();
          pendingText = t;
          if (ready) { // New connection prevents late frames from the aborted turn being mistaken
                       // for this reply.
            WebSocket old = socket;
            socket = null;
            ready = false;
            if (old != null) old.close(1000, "new text turn");
          }
          connect();
        });
  }

  private void sendText() {
    acceptTurn = true;
    String text = pendingText;
    pendingText = "";
    muted = false;
    uploading = false;
    send(json("type", "listen", "state", "detect", "text", text));
    setState("思考中");
  }

  private void onJson(JSONObject msg) throws Exception {
    String type = msg.optString("type"), phase = msg.optString("state");
    if (type.equals("hello")) {
      JSONObject ap = msg.optJSONObject("audio_params");
      int rate = ap == null ? 24000 : ap.optInt("sample_rate", 24000);
      if (ap != null
          && (!ap.optString("format", "opus").equals("opus") || ap.optInt("channels", 1) != 1)) {
        fail("网关必须使用 Opus 单声道");
        return;
      }
      if (!Protocol.validOutputRate(rate)) {
        fail("网关采样率不支持");
        return;
      }
      audio.configure(rate);
      session = msg.optString("session_id");
      ready = true;
      error = "";
      setState("待机");
      if (!pendingText.isEmpty()) sendText();
      else if (wantsListen && (!manual || pttHeld)) beginListen();
      return;
    }
    if (!ready) return;
    if (abortUntil > 0) {
      if (type.equals("tts") && phase.equals("stop") && msg.optBoolean("interrupted")) {
        abortUntil = 0;
        if (wantsListen) beginListen();
        return;
      }
      if (SystemClock.elapsedRealtime() < abortUntil && !type.equals("mcp")) return;
      abortUntil = 0;
    }
    if ((type.equals("stt") || type.equals("tts") || type.equals("vad")) && !acceptTurn) return;
    if (type.equals("stt")) {
      String t = msg.optString("text");
      if (!t.isEmpty() && !t.startsWith("% ")) {
        message("你", t);
        uploading = false;
        muted = false;
        io.removeCallbacks(listenTimeout);
        setState("思考中");
      }
    } else if (type.equals("vad")) {
      if (phase.equals("end")) {
        setState("识别中");
        io.removeCallbacks(listenTimeout);
        io.postDelayed(listenTimeout, 30000);
      } else if (phase.equals("empty")) {
        if (manual) {
          endInternal();
        } else {
          setState("聆听中");
          io.removeCallbacks(listenTimeout);
          io.postDelayed(listenTimeout, 10000);
        }
      }
    } else if (type.equals("tts")) {
      if (phase.equals("start")) {
        io.removeCallbacks(listenTimeout);
        uploading = false;
        muted = false;
        audio.newTurn();
        setState("思考中");
        io.postDelayed(listenTimeout, 120000);
      } else if (phase.equals("sentence_start") && !muted) {
        uploading = false;
        message("小麦", msg.optString("text"));
        setState("回复中");
      } else if (phase.equals("stop") && !muted) {
        long marker = turn;
        if (msg.optBoolean("interrupted")) audio.clear();
        audio.drained(
            () ->
                io.post(
                    () -> {
                      if (marker == turn && ready) {
                        manual = false;
                        beginListen();
                      }
                    }));
      }
    } else if (type.equals("mcp")) {
      JSONObject result = Mcp.handle(this, msg.optJSONObject("payload"));
      if (result != null) send(json("type", "mcp", "payload", result));
    } else if (type.equals("chat_history") && phase.equals("cleared")) {
      transcript.setLength(0);
      SharedPreferences.Editor ed = prefs.edit();
      for (String k : prefs.getAll().keySet()) if (k.startsWith("chat:")) ed.remove(k);
      ed.apply();
      publish();
    } else if (type.equals("server")) {
      error = msg.optString("text", msg.optString("message", "服务器通知"));
      publish();
    }
  }

  public void reconnect() {
    io.post(
        () -> {
          retry = true;
          wantsListen = false;
          pendingText = "";
          if (socket != null) {
            socket.cancel();
            socket = null;
          }
          ready = false;
          uploading = false;
          muted = true;
          audio.clear();
          turn++;
          audio.setWakeEnabled(prefs.getBoolean("wake", true));
          connect();
        });
  }

  public void clearLocal() {
    io.post(
        () -> {
          transcript.setLength(0);
          prefs.edit().remove("chat:" + threadEntry).apply();
          publish();
        });
  }

  public void selectThread(String entry) {
    io.post(
        () -> {
          endInternal();
          threadEntry = entry;
          transcript = new StringBuilder(prefs.getString("chat:" + entry, ""));
          prefs.edit().putString("threadEntry", entry).apply();
          publish();
        });
  }

  public boolean sleeping() {
    Calendar c = Calendar.getInstance();
    return prefs.getBoolean("sleep", false)
        && Protocol.inSleep(
            c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE),
            Protocol.minutes(prefs.getString("sleepStart", "22:00")),
            Protocol.minutes(prefs.getString("sleepEnd", "07:00")));
  }

  public void notifySettings() {
    io.post(this::publish);
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    retry = false;
    listener = null;
    io.removeCallbacksAndMessages(null);
    if (socket != null) socket.cancel();
    audio.stop();
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
    worker.quitSafely();
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    stopForeground(true);
    super.onDestroy();
  }
}

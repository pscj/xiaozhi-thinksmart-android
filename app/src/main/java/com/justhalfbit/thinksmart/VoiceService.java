package com.justhalfbit.thinksmart;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.util.Log;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.*;

/** One serial owner for protocol state; hardware audio lives on dedicated threads. */
public class VoiceService extends Service {
  public interface Listener {
    void update(Snapshot snapshot);
    void showVolume(int volume);
  }

  public static final class Snapshot {
    public final String state, error, managementError, entry, modelLabel;
    public final JSONArray messages, options;
    public final int reports;
    public final boolean sleeping, microphoneMuted, connected;
    public final long wakeSerial;

    public Snapshot(
        String state,
        String error,
        String managementError,
        String entry,
        String modelLabel,
        JSONArray messages,
        JSONArray options,
        int reports,
        boolean sleeping,
        boolean microphoneMuted,
        boolean connected,
        long wakeSerial) {
      this.state = state;
      this.error = error;
      this.managementError = managementError;
      this.entry = entry;
      this.modelLabel = modelLabel;
      this.messages = messages;
      this.options = options;
      this.reports = reports;
      this.sleeping = sleeping;
      this.microphoneMuted = microphoneMuted;
      this.connected = connected;
      this.wakeSerial = wakeSerial;
    }
  }

  private ChatLog chats;
  private String chatDevice;
  private ManagementClient management;
  private String managementError = "", modelLabel = "选择模型";
  private JSONArray modelOptions = new JSONArray();
  private int reports, managementEpoch, modelRequest, historyRequest, reportRequest, verifyRequest;
  private boolean modelBusy, historyBusy, reportBusy, verifying, lastQuiet;
  private long wakeSerial, questionStarted;
  private String managementDevice = "";
  private long lastForegroundSync;
  private WakeMatcher wakeMatcher;
  private boolean wakePending, botStarted, wakeGreetingPending, reportsPending;
  private final ArrayDeque<byte[]> wakeBacklog = new ArrayDeque<>();
  private final Runnable managementTick =
      new Runnable() {
        public void run() {
          if (destroyed) return;
          boolean quiet = sleeping();
          if (quiet != lastQuiet) {
            lastQuiet = quiet;
            refreshReports(false);
            // Leaving the sleep window: if the display blanked overnight, the
            // activity is stopped behind its black curtain and nothing else
            // would ever lift it — wake the screen and bring it forward.
            if (!quiet) wakeScreenAfterSleep();
          }
          if (SystemClock.elapsedRealtime() - lastForegroundSync >= 15000) {
            lastForegroundSync = SystemClock.elapsedRealtime();
            if (listener != null && !uploading) refreshModels();
            refreshReports(false);
          }
          publish();
          io.postDelayed(this, 1000);
        }
      };

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
  private MuseBle museBle;
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
  private volatile Listener listener;
  private PowerManager.WakeLock wakeLock;
  private final Runnable listenTimeout = () -> endInternal();
  // Opt-in diagnostics: adb shell setprop log.tag.VoiceTrace DEBUG.
  // A successful send only means OkHttp accepted the packet, not server receipt.
  private long traceAt;
  private int traceFrames, traceSpeech, traceQueued, traceRejected, traceBlocked;

  private void traceAudio(boolean speech, boolean allowed, boolean queued) {
    if (!Log.isLoggable("VoiceTrace", Log.DEBUG)) return;
    traceFrames++;
    if (speech) traceSpeech++;
    if (!allowed) traceBlocked++;
    else if (queued) traceQueued++;
    else traceRejected++;
    long now = SystemClock.elapsedRealtime();
    if (traceAt == 0) traceAt = now;
    if (now - traceAt < 1000) return;
    Log.d("VoiceTrace", "upload elapsedMs=" + (now - traceAt)
        + " frames=" + traceFrames + " speechFrames=" + traceSpeech
        + " queued=" + traceQueued + " rejected=" + traceRejected + " blocked=" + traceBlocked
        + " state=" + state + " ready=" + ready + " uploading=" + uploading
        + " micMuted=" + microphoneMuted() + " socket=" + (socket != null)
        + " queueBytes=" + (socket == null ? -1 : socket.queueSize()));
    traceAt = now;
    traceFrames = traceSpeech = traceQueued = traceRejected = traceBlocked = 0;
  }

  public void attach(Listener value) {
    listener = value;
    io.post(
        () -> {
          publish();
          syncManagement();
        });
  }

  public void detach() {
    listener = null;
  }

  void notifyVolume(int volume) {
    Listener current = listener;
    if (current != null)
      main.post(() -> {
        if (listener == current) current.showVolume(volume);
      });
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
    // Generate stable identities once; keep existing IDs across upgrades and reconnects.
    SharedPreferences.Editor identity = prefs.edit();
    if (prefs.getString("device", "").trim().isEmpty())
      identity.putString("device", UUID.randomUUID().toString());
    if (prefs.getString("client", "").trim().isEmpty())
      identity.putString("client", UUID.randomUUID().toString());
    identity.apply();
    museBle = new MuseBle(this, msg -> io.post(() -> send(msg)));
    threadEntry = prefs.getString("threadEntry", "default");
    chatDevice = prefs.getString("device", "legacy");
    chats = new ChatLog(prefs.getString("chatsV2", "{}"));
    if (!prefs.getBoolean("chatMigrationV2", false)) {
      for (String key : prefs.getAll().keySet())
        if (key.startsWith("chat:")) chats.migrate(key.substring(5), prefs.getString(key, ""));
      prefs.edit().putBoolean("chatMigrationV2", true).apply();
    }
    saveChats();
    try {
      wakeMatcher = new WakeMatcher(getAssets().open("kws-zh/pinyin.txt"));
    } catch (java.io.IOException ignored) {
    }
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
                io.post(() -> onAudioFrame(frame, speech));
              }

              public void wake(byte[] pcm) {
                io.post(() -> onLocalWake(pcm));
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
    io.post(
        () -> {
          resetManagement();
          connect();
          io.post(managementTick);
        });
  }

  private void onAudioFrame(byte[] frame, boolean speech) {
    if (verifying || wakePending) {
      if (wakeBacklog.size() == 150) wakeBacklog.removeFirst();
      wakeBacklog.addLast(frame);
    }
    if (speech && uploading && !manual && state.equals("聆听中")) {
      long now = SystemClock.elapsedRealtime();
      if (now - lastExtended > 250) {
        lastExtended = now;
        io.removeCallbacks(listenTimeout);
        io.postDelayed(
            listenTimeout,
            Math.max(0, Math.min(10000, listenStarted + 60000 - now)));
      }
    }
    boolean allowed = ready
        && uploading
        && socket != null
        && !microphoneMuted();
    boolean queued = false;
    if (allowed) {
      if (socket.queueSize() > 128 * 1024) {
        fail("上传积压，连接已重置");
        return;
      }
      queued = socket.send(ByteString.of(frame));
    }
    traceAudio(speech, allowed, queued);
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
    if (listener == null) return;
    Snapshot snapshot =
        new Snapshot(
            state,
            error,
            managementError,
            threadEntry,
            modelLabel,
            chats.array(threadEntry),
            modelOptions,
            reports,
            sleeping(),
            ((AudioManager) getSystemService(AUDIO_SERVICE)).isMicrophoneMute(),
            ready,
            wakeSerial);
    Listener l = listener;
    if (l != null)
      main.post(
          () -> {
            if (listener == l) l.update(snapshot);
          });
  }

  private void setState(String value) {
    if (Log.isLoggable("VoiceTrace", Log.DEBUG) && !state.equals(value))
      Log.d("VoiceTrace", "state " + state + " -> " + value);
    boolean ended = !state.equals("待机") && value.equals("待机");
    if (value.equals("聆听中") && !state.equals(value)) {
      listenStarted = SystemClock.elapsedRealtime();
      lastExtended = 0;
    }
    state = value;
    publish();
    if (ended) {
      syncManagement();
      questionStarted = 0;
    }
  }

  private void saveChats() {
    prefs
        .edit()
        .putString("chatsV2", chats.encode())
        .putString("chatsForDevice:" + chatDevice, chats.encode())
        .apply();
  }

  private void message(String role, String text) {
    if (role.equals("你")) {
      questionStarted = System.currentTimeMillis();
      botStarted = false;
      chats.add(threadEntry, "user", text);
    } else if (botStarted) chats.appendBot(threadEntry, text);
    else {
      chats.add(threadEntry, "bot", text);
      botStarted = true;
    }
    saveChats();
    publish();
  }

  private void connect() {
    if (destroyed || !retry || socket != null) return;
    String url = prefs.getString("ws", "").trim();
    if (url.isEmpty()) {
      error = "先在设置中填写服务器 IP";
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
                                    json("mcp", true, "vad_end", true, "ready_on_tts_start", true, "muse_pair", true),
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
      fail("连接地址无效，请在设置中重新保存服务器 IP");
    }
  }

  private void fail(String reason) {
    if (museBle != null) museBle.stop();
    if (destroyed) return;
    cancelVerification();
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
    boolean queued = socket.send(o.toString());
    if (Log.isLoggable("VoiceTrace", Log.DEBUG))
      Log.d("VoiceTrace", "send type=" + o.optString("type") + " phase=" + o.optString("state")
          + " mode=" + o.optString("mode") + " queued=" + queued);
    return queued;
  }

  public void wake() {
    io.post(
        () -> {
          if (microphoneMuted()) {
            main.post(
                () -> android.widget.Toast.makeText(
                    this, "麦克风已关，请打开设备的麦克风开关", android.widget.Toast.LENGTH_SHORT).show());
            return;
          }
          requestListen(false);
        });
  }

  boolean microphoneMuted() {
    return ((AudioManager) getSystemService(AUDIO_SERVICE)).isMicrophoneMute();
  }

  public void abort() {
    io.post(
        () -> {
          if (!ready || !(state.equals("回复中") || state.equals("思考中") || state.equals("识别中")))
            return;
          boolean keepListening = uploading && !state.equals("识别中");
          long played = audio.playedMs();
          cancelVerification();
          if (state.equals("回复中")) {
            chats.markInterrupted(threadEntry);
            saveChats();
          }
          turn++;
          wantsListen = reportsPending = false;
          pendingText = "";
          acceptTurn = false;
          muted = true;
          uploading = keepListening;
          audio.clear();
          io.removeCallbacks(listenTimeout);
          if (send(json("type", "abort", "played_ms", played))) awaitAbort();
          // Interrupting does not initiate a new recording; preserve an already open mic only.
          if (keepListening) {
            uploading = acceptTurn = true;
            setState("聆听中");
            io.postDelayed(listenTimeout, 10000);
          } else setState("待机");
        });
  }

  private void awaitAbort() {
    abortUntil = SystemClock.elapsedRealtime() + 3000;
    long marker = turn;
    io.postDelayed(
        () -> {
          if (marker == turn) {
            abortUntil = 0;
            resumeAfterAbort();
          }
        },
        3000);
  }

  private void resumeAfterAbort() {
    if (!ready || abortUntil > 0) return;
    if (!pendingText.isEmpty()) sendText();
    else if (wantsListen) beginListen();
    else if (reportsPending) {
      reportsPending = false;
      refreshReports(true);
    }
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
    requestListen(isManual, false);
  }

  private void requestListen(boolean isManual, boolean voiceWake) {
    retry = true;
    cancelVerification();
    wakeGreetingPending = voiceWake;
    wakeSerial++;
    questionStarted = System.currentTimeMillis();
    botStarted = false;
    manual = isManual;
    reportsPending = false;
    pendingText = "";
    if (!ready) {
      wantsListen = true;
      connect();
      return;
    }
    if (state.equals("回复中")) {
      chats.markInterrupted(threadEntry);
      saveChats();
    }
    if (abortUntil > 0 || state.equals("思考中") || state.equals("识别中") || state.equals("回复中")
        || (voiceWake && uploading)) {
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
    if (wakeGreetingPending) {
      wakeGreetingPending = false;
      wantsListen = false;
      abortUntil = 0;
      acceptTurn = true;
      muted = false;
      uploading = false;
      turn++;
      // KWS has already consumed the wake phrase. Notify the server explicitly;
      // uploading only the following silence cannot trigger its greeting.
      // Use the protocol's standard wake phrase, independent of local KWS names.
      send(json("type", "listen", "state", "detect", "mode", "realtime", "text", "嘿，你好呀"));
      listenDuringReply();
      setState("思考中");
      io.removeCallbacks(listenTimeout);
      io.postDelayed(listenTimeout, 30000);
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
    flushWakeBacklog();
    io.removeCallbacks(listenTimeout);
    io.postDelayed(listenTimeout, manual ? 60000 : 10000);
  }

  private void listenDuringReply() {
    if (manual || uploading) return;
    // Keep the microphone stream open through TTS; server VAD/ASR handles barge-in.
    uploading = true;
    send(json("type", "listen", "state", "start", "mode", "realtime"));
  }

  public void end() {
    io.post(
        () -> {
          retry = false;
          pttHeld = false;
          manual = false;
          endInternal();
          // Detach before closing so queued messages and close callbacks cannot restart the turn.
          WebSocket old = socket;
          socket = null;
          ready = false;
          abortUntil = 0;
          error = "";
          if (old != null && !old.close(1000, "conversation ended")) old.cancel();
          publish();
        });
  }

  private void endInternal() {
    cancelVerification();
    botStarted = false;
    if (state.equals("回复中")) {
      chats.markInterrupted(threadEntry);
      saveChats();
    }
    turn++;
    acceptTurn = false;
    wantsListen = false;
    reportsPending = false;
    pendingText = "";
    abortUntil = 0;
    if (uploading) send(json("type", "listen", "state", "stop"));
    if (ready
        && !state.equals("待机")
        && send(json("type", "abort", "played_ms", audio.playedMs()))) {
      awaitAbort();
    }
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
          retry = true;
          endInternal();
          questionStarted = System.currentTimeMillis();
          pendingText = t;
          if (ready) {
            if (abortUntil == 0) sendText();
            else setState("思考中");
          } else connect();
        });
  }

  private void sendText() {
    abortUntil = 0;
    botStarted = false;
    turn++;
    acceptTurn = true;
    String text = pendingText;
    pendingText = "";
    muted = false;
    uploading = false;
    send(json("type", "listen", "state", "detect", "text", text));
    setState("思考中");
    io.removeCallbacks(listenTimeout);
    io.postDelayed(listenTimeout, 30000);
  }

  private void onJson(JSONObject msg) throws Exception {
    String type = msg.optString("type"), phase = msg.optString("state");
    if (Log.isLoggable("VoiceTrace", Log.DEBUG)
        && (type.equals("hello") || type.equals("vad") || type.equals("stt") || type.equals("tts")))
      Log.d("VoiceTrace", "receive type=" + type + " phase=" + phase
          + " textLength=" + msg.optString("text").length()
          + " interrupted=" + msg.optBoolean("interrupted") + " acceptTurn=" + acceptTurn);
    if (type.equals("muse_pair")) { museBle.handle(msg); return; }
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
      abortUntil = 0;
      session = msg.optString("session_id");
      ready = true;
      error = "";
      muted = false;
      syncManagement();
      setState("待机");
      if (!pendingText.isEmpty()) sendText();
      else if (wantsListen && (!manual || pttHeld)) beginListen();
      else if (reportsPending) resumeAfterAbort();
      return;
    }
    if (!ready) return;
    if (abortUntil > 0) {
      if (type.equals("tts") && phase.equals("stop") && msg.optBoolean("interrupted")) {
        abortUntil = 0;
        resumeAfterAbort();
        return;
      }
      if (SystemClock.elapsedRealtime() < abortUntil) {
        if (!type.equals("mcp")) return;
      } else {
        abortUntil = 0;
        resumeAfterAbort();
        if (!type.equals("mcp")) return;
      }
    }
    // Server announcements may start with sentence_start, without a user turn or tts/start.
    if (type.equals("tts")
        && (phase.equals("start") || phase.equals("sentence_start"))
        && !acceptTurn
        && abortUntil == 0) {
      botStarted = false;
      acceptTurn = true;
      muted = false;
      audio.newTurn();
    }
    if ((type.equals("stt") || type.equals("tts") || type.equals("vad")) && !acceptTurn) return;
    if (type.equals("stt")) {
      String t = msg.optString("text");
      if (!t.isEmpty() && !t.startsWith("% ")) {
        // In realtime mode the next utterance can begin while this reply is generated.
        turn++;
        message("你", t);
        muted = false;
        io.removeCallbacks(listenTimeout);
        refreshModels();
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
        botStarted = false;
        io.removeCallbacks(listenTimeout);
        turn++;
        muted = false;
        audio.newTurn();
        listenDuringReply();
        setState("思考中");
        io.postDelayed(listenTimeout, 120000);
      } else if (phase.equals("sentence_start") && !muted) {
        listenDuringReply();
        message("小麦", msg.optString("text"));
        setState("回复中");
      } else if (phase.equals("stop") && !muted) {
        long marker = turn;
        if (msg.optBoolean("interrupted")) {
          audio.clear();
          chats.markInterrupted(threadEntry);
          saveChats();
        }
        audio.drained(
            () ->
                io.post(
                    () -> {
                      if (marker == turn && ready) {
                        refreshModels();
                        refreshHistory();
                        refreshReports(false);
                        manual = false;
                        if (uploading) {
                          // Do not reset the server ASR buffer: the user may already be speaking.
                          if (state.equals("回复中") || state.equals("思考中")) {
                            listenStarted = SystemClock.elapsedRealtime();
                            setState("聆听中");
                            io.removeCallbacks(listenTimeout);
                            io.postDelayed(listenTimeout, 10000);
                          }
                        } else beginListen();
                      }
                    }));
      }
    } else if (type.equals("mcp")) {
      JSONObject result = Mcp.handle(this, msg.optJSONObject("payload"));
      if (result != null) send(json("type", "mcp", "payload", result));
    } else if (type.equals("chat_history") && phase.equals("cleared")) {
      historyRequest++;
      historyBusy = false;
      chats.clearAll();
      saveChats();
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
          resetManagement();
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
          historyRequest++;
          historyBusy = false;
          chats.clear(threadEntry);
          saveChats();
          prefs.edit().remove("chat:" + threadEntry).apply();
          publish();
        });
  }

  public void clearServer(boolean memoryOnly) {
    io.post(
        () -> {
          if (management == null) return;
          int epoch = managementEpoch;
          management.post(
              "kiosk/chat/clear",
              json("device", device(), "memory_only", memoryOnly),
              (data, err, status) ->
                  io.post(
                      () -> {
                        if (destroyed || epoch != managementEpoch) return;
                        if (data == null) {
                          managementError = err;
                          publish();
                          return;
                        }
                        managementError = memoryOnly ? "服务器记忆已清除" : "服务器记录与记忆已清除";
                        if (!memoryOnly) {
                          historyRequest++;
                          historyBusy = false;
                          chats.clearAll();
                          saveChats();
                          SharedPreferences.Editor editor = prefs.edit();
                          for (String key : prefs.getAll().keySet())
                            if (key.startsWith("chat:")) editor.remove(key);
                          editor.apply();
                        }
                        publish();
                      }));
        });
  }

  private String device() {
    return prefs.getString("device", "");
  }

  private void resetManagement() {
    if (!managementDevice.isEmpty() && !managementDevice.equals(device())) {
      saveChats();
      chatDevice = device();
      chats = new ChatLog(prefs.getString("chatsForDevice:" + chatDevice, "{}"));
      saveChats();
      threadEntry = "default";
      prefs.edit().putString("threadEntry", threadEntry).apply();
    }
    managementDevice = device();
    chatDevice = device();
    managementEpoch++;
    modelRequest++;
    historyRequest++;
    reportRequest++;
    cancelVerification();
    modelBusy = historyBusy = reportBusy = false;
    if (management != null) management.close();
    management =
        new ManagementClient(prefs.getString("http", ""), prefs.getString("adminToken", ""));
    managementError = "";
    reports = 0;
    modelOptions = new JSONArray();
    lastQuiet = sleeping();
    syncManagement();
  }

  public void refreshManagement() {
    io.post(this::syncManagement);
  }

  private void syncManagement() {
    refreshModels();
    refreshHistory();
    refreshReports(false);
  }

  private void refreshModels() {
    if (management == null || !management.configured() || modelBusy) return;
    modelBusy = true;
    int epoch = managementEpoch, request = ++modelRequest;
    management.get(
        "kiosk/llm",
        device(),
        (data, err, status) ->
            io.post(
                () -> {
                  if (destroyed || epoch != managementEpoch || request != modelRequest) return;
                  modelBusy = false;
                  if (data == null) {
                    managementError = err;
                    publish();
                    return;
                  }
                  managementError = "";
                  applyModels(data);
                  publish();
                }));
  }

  private void applyModels(JSONObject data) {
    JSONArray options = data.optJSONArray("options");
    modelOptions = options == null ? new JSONArray() : options;
    String selected = data.optString("selected", data.optString("default", threadEntry));
    if (!selected.isEmpty() && !selected.equals(threadEntry)) {
      if (questionStarted > 0) chats.moveLocalSince(threadEntry, selected, questionStarted);
      threadEntry = selected;
      prefs.edit().putString("threadEntry", selected).apply();
      saveChats();
      refreshHistory();
    }
    modelLabel = selected;
    for (int i = 0; i < modelOptions.length(); i++) {
      JSONObject option = modelOptions.optJSONObject(i);
      if (option != null && selected.equals(option.optString("entry")))
        modelLabel = option.optString("label", selected);
    }
  }

  public void chooseModel(JSONObject option) {
    io.post(
        () -> {
          if (option.optString("entry").equals(threadEntry)) return;
          if (management == null) return;
          endInternal();
          modelRequest++;
          modelBusy = false;
          int epoch = managementEpoch, request = ++modelRequest;
          modelBusy = true;
          management.post(
              "kiosk/llm/use",
              json(
                  "device",
                  device(),
                  "kind",
                  option.optString("kind"),
                  "vendor",
                  option.optString("vendor"),
                  "entry",
                  option.optString("entry")),
              (data, err, status) ->
                  io.post(
                      () -> {
                        if (destroyed || epoch != managementEpoch || request != modelRequest)
                          return;
                        modelBusy = false;
                        if (data == null) {
                          managementError = err;
                          publish();
                          return;
                        }
                        questionStarted = 0;
                        applyModels(data);
                        refreshHistory();
                        publish();
                      }));
        });
  }

  private void refreshHistory() {
    if (management == null
        || !management.configured()
        || historyBusy
        || !(state.equals("待机") || state.equals("未连接") || state.equals("连接中"))) return;
    historyBusy = true;
    int epoch = managementEpoch, request = ++historyRequest;
    management.get(
        "kiosk/chat/history",
        device(),
        (data, err, status) ->
            io.post(
                () -> {
                  if (destroyed || epoch != managementEpoch || request != historyRequest) return;
                  historyBusy = false;
                  if (data == null) {
                    managementError = err;
                    publish();
                    return;
                  }
                  if (!(state.equals("待机") || state.equals("未连接") || state.equals("连接中"))) return;
                  JSONObject models = data.optJSONObject("models");
                  if (models != null) {
                    Iterator<String> keys = models.keys();
                    while (keys.hasNext()) {
                      String key = keys.next();
                      JSONArray list = models.optJSONArray(key);
                      if (list != null) chats.merge(key, list);
                    }
                    saveChats();
                  }
                  publish();
                }));
  }

  public void playReports() {
    io.post(
        () -> {
          if (management == null || !management.configured()) {
            managementError = "请先在设置中填写服务器 IP";
            publish();
            return;
          }
          reportsPending = true;
          retry = true;
          if (ready) resumeAfterAbort();
          else connect();
        });
  }

  private void refreshReports(boolean play) {
    if (management == null || !management.configured() || (reportBusy && !play)) return;
    reportBusy = true;
    int epoch = managementEpoch, request = ++reportRequest;
    management.post(
        "kiosk/reports",
        json("device", device(), "quiet", sleeping(), "play", play),
        (data, err, status) ->
            io.post(
                () -> {
                  if (destroyed || epoch != managementEpoch || request != reportRequest) return;
                  reportBusy = false;
                  if (data == null) managementError = err;
                  else {
                    reports = data.optInt("count");
                    if (play) managementError = data.optBoolean("playing") ? "已安排播报" : "当前没有可播放的汇报";
                  }
                  publish();
                }));
  }

  private void cancelVerification() {
    verifyRequest++;
    verifying = false;
    wakePending = false;
    wakeGreetingPending = false;
    wakeBacklog.clear();
  }

  private void flushWakeBacklog() {
    if (uploading && ready && socket != null) {
      for (byte[] packet : wakeBacklog) socket.send(ByteString.of(packet));
      wakeBacklog.clear();
      wakePending = false;
    }
  }

  private void acceptVoiceWake() {
    requestListen(false, true);
    main.post(
        () -> {
          if (destroyed) return;
          PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
          if (!pm.isInteractive()) {
            PowerManager.WakeLock screen =
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "thinksmart:wake-screen");
            screen.acquire(3000);
            try {
              startActivity(
                  new Intent(this, MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                      .putExtra("voiceWake", true));
            } catch (RuntimeException ignored) {
              /* The ROM may require the notification to open the app. */
            }
          }
        });
  }

  /** The sleep window just ended: relight a display that blanked overnight. */
  private void wakeScreenAfterSleep() {
    main.post(
        () -> {
          if (destroyed) return;
          PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
          if (pm.isInteractive()) return;
          PowerManager.WakeLock screen =
              pm.newWakeLock(
                  PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                  "thinksmart:sleep-restore");
          screen.acquire(3000);
          try {
            startActivity(
                new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("sleepRestore", true));
          } catch (RuntimeException ignored) {
            /* The ROM may require the notification to open the app. */
          }
        });
  }

  private void onLocalWake(byte[] pcm) {
    // Realtime listening is also wakeable: repeated wake phrases must not be silently ignored.
    if (destroyed || abortUntil > 0 || (manual && uploading)) return;
    verifyWake(pcm);
  }

  private void verifyWake(byte[] pcm) {
    if (verifying || microphoneMuted()) return;
    if (!prefs.getBoolean("wakeVerify", false) || management == null || !management.configured()) {
      acceptVoiceWake();
      return;
    }
    verifying = true;
    int epoch = managementEpoch, request = ++verifyRequest;
    management.verify(
        pcm,
        (data, err, status) ->
            io.post(
                () -> {
                  if (destroyed || epoch != managementEpoch || request != verifyRequest) return;
                  verifying = false;
                  if (data == null
                      || (wakeMatcher != null
                          ? wakeMatcher.matches(data.optString("text"), prefs.getString(WakePhrase.KEY, ""))
                          : (!prefs.getString(WakePhrase.KEY, "").isEmpty()
                              || Protocol.isWakeText(data.optString("text"))))) acceptVoiceWake();
                  else wakeBacklog.clear();
                }));
  }

  public float audioLevel(boolean playing) {
    AudioEngine engine = audio;
    return engine == null ? 0 : engine.level(playing);
  }

  public boolean previewRecentAudio() {
    return audio != null && audio.previewRecentAudio();
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
    io.post(
        () -> {
          publish();
          refreshReports(false);
        });
  }

  @Override
  public void onDestroy() {
    if (museBle != null) museBle.release();
    destroyed = true;
    retry = false;
    listener = null;
    io.removeCallbacksAndMessages(null);
    if (socket != null) socket.cancel();
    audio.stop();
    if (management != null) management.close();
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
    worker.quitSafely();
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    stopForeground(true);
    super.onDestroy();
  }
}

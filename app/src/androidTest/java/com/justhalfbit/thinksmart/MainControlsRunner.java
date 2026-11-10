package com.justhalfbit.thinksmart;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

/** Isolated service tests: no real microphone, gateway, settings or chat history are used. */
public final class MainControlsRunner extends Instrumentation {
  private int passed;

  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      try (Harness h = new Harness()) {
        h.set("state", "回复中");
        h.service.abort(); h.drain();
        h.check("待机".equals(h.get("state")), "打断回答应回到待机");
        h.ack();
        h.check(h.count("listen", "start") == 0 && !h.socket.closed, "打断不能新开收音或断开连接");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.set("state", "思考中"); h.set("uploading", true);
        h.service.abort(); h.drain();
        h.check(Boolean.TRUE.equals(h.get("uploading")), "已有收音应保留");
        h.check(h.count("listen", "stop") == 0, "保留收音时不能提交结束录音");
        passed++;
      }
      try (ReportServer server = new ReportServer(); Harness h = new Harness()) {
        h.set("management", new ManagementClient("http://127.0.0.1:" + server.port(), ""));
        h.set("state", "回复中");
        h.service.chooseModel(new JSONObject().put("entry", "default")); h.drain();
        h.check("回复中".equals(h.get("state")) && h.socket.messages.isEmpty(), "重复选择当前模型不能中断");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.service.text("测试提问"); h.drain();
        h.check(h.get("socket") == h.socket && !h.socket.closed, "文字提问必须复用连接");
        h.check(h.count("listen", "detect") == 1, "文字提问必须发送");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.set("state", "回复中");
        h.service.text("新问题"); h.drain();
        h.json(new JSONObject().put("type", "mcp"));
        h.json(new JSONObject().put("type", "tts").put("state", "sentence_start").put("text", "旧回答"));
        h.check(h.count("listen", "detect") == 0, "中断确认前不能提交新问题");
        h.check(((ChatLog) h.get("chats")).array("default").length() == 0, "应丢弃旧回答");
        h.ack();
        h.check(h.count("listen", "detect") == 1 && !h.socket.closed, "确认后在同一连接提交问题");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.set("state", "回复中");
        h.service.text("超时回退"); h.drain();
        Thread.sleep(3200); h.drain();
        h.check(h.count("listen", "detect") == 1, "中断确认丢失时应限时继续");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.set("state", "回复中");
        h.service.text("取消的问题"); h.service.end(); h.drain();
        Thread.sleep(3200); h.drain();
        h.check(h.count("listen", "detect") == 0 && h.socket.closed, "结束应取消待发问题");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.service.micMuted = true;
        h.service.wake(); h.drain();
        h.check("待机".equals(h.get("state")) && h.socket.messages.isEmpty(), "麦克风关闭时短按不能开始收音");
        passed++;
      }
      try (ReportServer server = new ReportServer(); Harness h = new Harness()) {
        h.set("management", new ManagementClient("http://127.0.0.1:" + server.port(), ""));
        h.set("ready", false); h.set("state", "连接中");
        h.service.playReports(); h.drain();
        h.check(server.plays == 0 && Boolean.TRUE.equals(h.get("reportsPending")), "播报应等待连接握手");
        h.json(new JSONObject().put("type", "hello").put("session_id", "test"));
        long until = SystemClock.elapsedRealtime() + 5000;
        while (server.plays == 0 && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
        h.check(server.plays == 1, "握手完成后应请求播报一次");
        h.check(!Boolean.TRUE.equals(h.get("uploading")), "收听汇报不能开启麦克风上传");
        passed++;
      }
      try (ReportServer server = new ReportServer(); Harness h = new Harness()) {
        h.set("management", new ManagementClient("http://127.0.0.1:" + server.port(), ""));
        h.set("state", "回复中");
        h.service.playReports(); h.drain();
        long until = SystemClock.elapsedRealtime() + 5000;
        while (server.plays == 0 && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
        h.check(server.plays == 1 && !h.socket.closed, "已连接时应复用连接请求汇报，不要求先结束");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.set("wakeGreetingPending", true);
        h.call("beginListen");
        h.check(h.count("listen", "detect") == 1 && h.count("listen", "start") == 1,
            "语音唤醒应同时通知问候并开启实时上传");
        h.json(new JSONObject().put("type", "tts").put("state", "start"));
        h.json(new JSONObject().put("type", "tts").put("state", "sentence_start").put("text", "我在，请说"));
        h.frame();
        h.check(h.socket.frames == 1 && Boolean.TRUE.equals(h.get("uploading")), "问候播报中必须上传插话音频");
        h.check(h.count("listen", "start") == 1, "TTS不能重置正在识别的音频");
        h.ack(); h.frame();
        h.json(new JSONObject().put("type", "stt").put("text", "今天天气怎么样"));
        h.frame();
        h.check(h.socket.frames == 3 && "思考中".equals(h.get("state")), "打断确认及识别后继续上传");
        h.check(((ChatLog) h.get("chats")).array("default").toString().contains("今天天气怎么样"), "插话识别结果应显示");
        h.service.end(); h.drain(); h.frame();
        h.check(h.socket.frames == 3, "结束会话后不能上传音频");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.set("acceptTurn", true); h.set("manual", true);
        h.json(new JSONObject().put("type", "tts").put("state", "start")); h.frame();
        h.check(h.socket.frames == 0 && h.count("listen", "start") == 0, "手动模式播放不能自动收音");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.set("state", "回复中"); h.set("uploading", true); h.set("acceptTurn", true);
        h.localWake();
        h.check(h.count("abort", "") == 1, "播报上传期间唤醒应发送打断");
        h.ack();
        h.check(h.count("listen", "detect") == 1, "打断确认后应重新唤醒");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.set("state", "聆听中"); h.set("uploading", true);
        h.localWake();
        h.check(h.count("abort", "") == 1, "聆听上传中再次唤醒必须先取消旧识别");
        h.localWake();
        h.check(h.count("abort", "") == 1, "等待打断确认时不能反复发送打断");
        h.ack();
        h.check(h.count("listen", "detect") == 1 && h.count("listen", "start") == 1,
            "聆听中重新唤醒必须发问候并恢复上传");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.set("state", "回复中"); h.set("manual", true);
        h.set("uploading", true);
        h.localWake();
        h.check(h.socket.messages.isEmpty(), "手动模式不应由唤醒词打断");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.set("state", "识别中"); h.set("listenStarted", 0L);
        h.set("acceptTurn", true);
        h.json(new JSONObject().put("type", "vad").put("state", "empty"));
        h.check(((Long) h.get("listenStarted")) > 0L, "重新聆听必须刷新旧计时");
        h.set("uploading", true);
        h.frame();
        Thread.sleep(2800); h.drain();
        h.check("聆听中".equals(h.get("state")) && Boolean.TRUE.equals(h.get("uploading")),
            "语音活动之后2.5秒不应提前结束聆听");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.json(new JSONObject().put("type", "tts").put("state", "sentence_start").put("text", "主动播报"));
        h.frame();
        h.check(h.socket.frames == 1 && h.count("listen", "start") == 1, "没有tts/start的播报也允许插话");
        h.service.micMuted = true; h.frame();
        h.check(h.socket.frames == 1, "实体静音时不能上传");
        passed++;
      }
      try (Harness h = new Harness()) {
        h.set("state", "聆听中"); h.set("uploading", true); h.set("acceptTurn", true);
        ((ChatLog) h.get("chats")).add("default", "user", "需要保留的历史");
        h.handler.post((Runnable) h.get("listenTimeout")); h.drain();
        h.check("待机".equals(h.get("state")), "聆听超时应退出对话");
        h.check(!Boolean.TRUE.equals(h.get("uploading")) && !Boolean.TRUE.equals(h.get("acceptTurn")),
            "超时后停止上传及旧轮次");
        h.check(Boolean.TRUE.equals(h.get("ready")) && !h.socket.closed, "待机可以保留网关连接");
        h.frame();
        h.check(h.socket.frames == 0, "待机连接不能继续上传音频");
        h.check(((ChatLog) h.get("chats")).array("default").length() == 1, "超时保留聊天历史");
        h.ack();
        h.service.wake(); h.drain();
        h.check("聆听中".equals(h.get("state")) && Boolean.TRUE.equals(h.get("uploading")),
            "待机后可复用连接重新唤醒");
        passed++;
      }
      result.putInt("passed", passed);
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      result.putInt("passed", passed);
      result.putString("error", e.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private static final class TestService extends VoiceService {
    boolean micMuted;
    TestService(Context context) { attachBaseContext(context); }
    @Override boolean microphoneMuted() { return micMuted; }
  }

  private final class Harness implements AutoCloseable {
    final TestService service = new TestService(getTargetContext());
    final FakeSocket socket = new FakeSocket();
    final HandlerThread thread = new HandlerThread("main-controls-test");
    final Handler handler;
    Harness() throws Exception {
      thread.start(); handler = new Handler(thread.getLooper());
      set("io", handler);
      set("prefs", getTargetContext().getSharedPreferences("main-controls-test", Context.MODE_PRIVATE));
      set("chats", new ChatLog("{}"));
      set("chatDevice", "test");
      set("audio", new AudioEngine(getTargetContext(), null));
      set("socket", socket); set("ready", true);
    }
    Object get(String name) throws Exception { Field f = VoiceService.class.getDeclaredField(name); f.setAccessible(true); return f.get(service); }
    void set(String name, Object value) throws Exception { Field f = VoiceService.class.getDeclaredField(name); f.setAccessible(true); f.set(service, value); }
    void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    void drain() throws Exception { CountDownLatch done = new CountDownLatch(1); handler.post(done::countDown); check(done.await(5, TimeUnit.SECONDS), "服务线程超时"); }
    void json(JSONObject json) throws Exception {
      FutureTask<Void> task = new FutureTask<>(() -> {
        Method m = VoiceService.class.getDeclaredMethod("onJson", JSONObject.class);
        m.setAccessible(true); m.invoke(service, json); return null;
      });
      handler.post(task); task.get(5, TimeUnit.SECONDS);
    }
    void call(String name) throws Exception {
      FutureTask<Void> task = new FutureTask<>(() -> {
        Method m = VoiceService.class.getDeclaredMethod(name);
        m.setAccessible(true); m.invoke(service); return null;
      });
      handler.post(task); task.get(5, TimeUnit.SECONDS);
    }
    void frame() throws Exception {
      FutureTask<Void> task = new FutureTask<>(() -> {
        Method m = VoiceService.class.getDeclaredMethod("onAudioFrame", byte[].class, boolean.class);
        m.setAccessible(true); m.invoke(service, new byte[] {1, 2}, true); return null;
      });
      handler.post(task); task.get(5, TimeUnit.SECONDS);
    }
    void localWake() throws Exception {
      FutureTask<Void> task = new FutureTask<>(() -> {
        Method m = VoiceService.class.getDeclaredMethod("onLocalWake", byte[].class);
        m.setAccessible(true); m.invoke(service, (Object) new byte[] {1, 2}); return null;
      });
      handler.post(task); task.get(5, TimeUnit.SECONDS);
    }
    void ack() throws Exception { json(new JSONObject().put("type", "tts").put("state", "stop").put("interrupted", true)); }
    int count(String type, String state) throws Exception {
      int count = 0;
      for (String value : socket.messages) {
        JSONObject msg = new JSONObject(value);
        if (type.equals(msg.optString("type")) && state.equals(msg.optString("state"))) count++;
      }
      return count;
    }
    @Override public void close() throws Exception {
      set("destroyed", true);
      ManagementClient management = (ManagementClient) get("management");
      if (management != null) management.close();
      handler.removeCallbacksAndMessages(null); thread.quitSafely(); thread.join(1000);
      service.settings().edit().clear().commit();
    }
  }

  private static final class FakeSocket implements WebSocket {
    final List<String> messages = new CopyOnWriteArrayList<>();
    boolean closed;
    int frames;
    public Request request() { return new Request.Builder().url("http://127.0.0.1/").build(); }
    public long queueSize() { return 0; }
    public boolean send(String text) { messages.add(text); return true; }
    public boolean send(ByteString bytes) { frames++; return true; }
    public boolean close(int code, String reason) { closed = true; return true; }
    public void cancel() { closed = true; }
  }

  private static final class ReportServer implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    final Thread thread;
    volatile int plays;
    ReportServer() throws IOException {
      thread = new Thread(() -> {
        while (!server.isClosed()) {
          try (Socket socket = server.accept()) {
            socket.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String line; int length = 0;
            while ((line = in.readLine()) != null && !line.isEmpty())
              if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            char[] body = new char[length]; int read = 0, n;
            while (read < length && (n = in.read(body, read, length - read)) > 0) read += n;
            if (new String(body).contains("\"play\":true")) plays++;
            byte[] response = "{\"ok\":true,\"options\":[],\"models\":{},\"count\":1,\"playing\":true}".getBytes("UTF-8");
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: " + response.length + "\r\n\r\n").getBytes("UTF-8"));
            out.write(response); out.flush();
          } catch (IOException e) { if (!server.isClosed()) throw new RuntimeException(e); }
        }
      }, "report-test-server");
      thread.start();
    }
    int port() { return server.getLocalPort(); }
    @Override public void close() throws Exception { server.close(); thread.join(1000); }
  }
}

package com.justhalfbit.thinksmart;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import org.json.JSONArray;
import org.json.JSONObject;

/** Captures deterministic native UI states without sending messages or changing stored history. */
public final class UiSnapshotRunner extends Instrumentation {
  private String scene;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    scene = args.getString("scene", "idle");
    start();
  }

  @Override
  public void onStart() {
    MainActivity activity = null;
    VoiceService service = null;
    final OrbView[] previewOrb = {null};
    android.os.PowerManager manager =
        (android.os.PowerManager)
            getTargetContext().getSystemService(android.content.Context.POWER_SERVICE);
    android.os.PowerManager.WakeLock wake =
        manager.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "thinksmart:ui-preview");
    wake.acquire(30000);
    try {
      Intent intent =
          new Intent(getTargetContext(), MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = (MainActivity) startActivitySync(intent);
      Thread.sleep(1200);
      Field field = MainActivity.class.getDeclaredField("service");
      field.setAccessible(true);
      service = (VoiceService) field.get(activity);
      if (service != null) service.detach();
      final MainActivity current = activity;
      String state =
          scene.equals("connecting")
              ? "连接中"
              : scene.equals("listening") || scene.equals("ptt")
              ? "聆听中"
              : scene.equals("recognizing")
                  ? "识别中"
                  : scene.equals("thinking") ? "思考中" : scene.equals("speaking") ? "回复中" : "待机";
      JSONArray options =
          new JSONArray()
              .put(
                  new JSONObject()
                      .put("kind", "api")
                      .put("vendor", "claude-code")
                      .put("label", "Claude")
                      .put("entry", "ClaudeCLI")
                      .put("model", "claude-sonnet-4-5"));
      JSONArray messages = new JSONArray();
      if (!scene.equals("idle") && !scene.equals("volume")
          && !scene.equals("connecting") && !scene.equals("ptt")) {
        messages.put(
            new JSONObject()
                .put("id", 1)
                .put("role", "user")
                .put("text", "明天北京天气怎么样？适合去爬山吗？")
                .put("time", System.currentTimeMillis() / 1000));
        if (scene.equals("speaking") || scene.equals("cut"))
          messages.put(
              new JSONObject()
                  .put("id", 2)
                  .put("role", "bot")
                  .put("text", "明天北京晴转多云，气温 9 到 19 度，北风二三级，空气质量良。这样的天气很适合去爬山，香山的红叶正是最好看的时候。")
                  .put("time", System.currentTimeMillis() / 1000)
                  .put("interrupted", scene.equals("cut")));
      }
      VoiceService.Snapshot value =
          new VoiceService.Snapshot(
              state,
              "",
              "",
              "ClaudeCLI",
              "Claude",
              messages,
              options,
              0,
              false,
              false,
              !scene.equals("idle") && !scene.equals("volume") && !scene.equals("connecting"),
              0);
      Field orbField = MainActivity.class.getDeclaredField("orb");
      orbField.setAccessible(true);
      OrbView orb = (OrbView) orbField.get(activity);
      previewOrb[0] = orb;
      final VoiceService boundService = service;
      if (scene.equals("ptt")) {
        Field held = MainActivity.class.getDeclaredField("microphoneHeld");
        held.setAccessible(true);
        held.setBoolean(activity, true);
      }
      runOnMainSync(
          () -> {
            current.update(value);
            // The upstream still-frame demo uses a normalized sound level of 0.7.
            orb.setLevelSource(playing -> playing ? .1167f : .00912f);
            if (scene.equals("volume")) current.showVolume(60);
          });
      if (scene.equals("history-persistence")) {
        // Include enough rows to exceed the screen height, as real saved histories do.
        for (int i = 1; i < 100; i++)
          messages.put(new JSONObject().put("id", i + 1).put("role", "bot").put("text", "已保存的消息 " + i));
        // Exercise the real rendering path as a saved conversation loses its connection.
        for (String phase : new String[] {"待机", "未连接", "连接中", "待机"}) {
          VoiceService.Snapshot saved =
              new VoiceService.Snapshot(
                  phase, "", "", "ClaudeCLI", "Claude", messages, options,
                  0, false, false, false, 0);
          runOnMainSync(
              () -> {
                current.update(saved);
                assertHistoryVisible(current, true);
              });
          waitForIdleSync();
          runOnMainSync(() -> assertLastMessageVisible(current));
        }
        // The welcome screen remains available when there really is no history.
        VoiceService.Snapshot empty =
            new VoiceService.Snapshot(
                "待机", "", "", "ClaudeCLI", "Claude", new JSONArray(), options,
                0, false, false, false, 0);
        runOnMainSync(
            () -> {
              current.update(empty);
              assertHistoryVisible(current, false);
              current.update(value);
            });
      }
      if (scene.equals("session-timeout")) {
        for (String phase : new String[] {"聆听中", "待机", "思考中", "回复中", "待机", "连接中", "识别中", "待机"}) {
          VoiceService.Snapshot transition =
              new VoiceService.Snapshot(
                  phase, "", "", "ClaudeCLI", "Claude", messages, options,
                  0, false, false, true, 0);
          runOnMainSync(
              () -> {
                current.update(transition);
                boolean inConversation = Protocol.conversationActive(phase);
                assertConversationUi(current, inConversation, inConversation || phase.equals("连接中"));
              });
        }
      }
      if (scene.equals("screen-idle")) {
        VoiceService.Snapshot standby = new VoiceService.Snapshot(
            "待机", "", "", "ClaudeCLI", "Claude", messages, options, 0, false, false, true, 0);
        VoiceService.Snapshot reconnect = new VoiceService.Snapshot(
            "连接中", "", "", "ClaudeCLI", "Claude", messages, options, 0, false, false, false, 0);
        runOnMainSync(() -> verifyScreenIdle(current, standby, reconnect));
      }
      Thread.sleep(600);
      android.view.accessibility.AccessibilityNodeInfo root = null;
      for (int attempt = 0; attempt < 10 && root == null; attempt++) {
        root = getUiAutomation().getRootInActiveWindow();
        if (root == null) Thread.sleep(500);
      }
      if (root == null || !"com.justhalfbit.thinksmart".contentEquals(root.getPackageName()))
        throw new IllegalStateException(
            "预览窗口未显示: " + (root == null ? "无窗口" : root.getPackageName()));
      Bitmap image = getUiAutomation().takeScreenshot();
      if (image == null) throw new IllegalStateException("无法读取屏幕");
      boolean allBlack = true;
      for (int y = 0; y < image.getHeight(); y += Math.max(1, image.getHeight() / 8))
        for (int x = 0; x < image.getWidth(); x += Math.max(1, image.getWidth() / 8))
          if ((image.getPixel(x, y) & 0xffffff) != 0) allBlack = false;
      if (allBlack) throw new IllegalStateException("屏幕处于休眠或被遮挡");
      File file = new File(getTargetContext().getExternalFilesDir(null), "ui-" + scene + ".png");
      try (FileOutputStream out = new FileOutputStream(file)) {
        image.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      Bundle result = new Bundle();
      result.putString("screenshot", file.getAbsolutePath());
      sendStatus(Activity.RESULT_OK, result);
      runOnMainSync(
          () ->
              orb.setLevelSource(
                  playing -> boundService == null ? 0 : boundService.audioLevel(playing)));
      if (service != null) service.attach(activity);
      if (wake.isHeld()) wake.release();
      finish(Activity.RESULT_OK, new Bundle());
    } catch (Exception e) {
      final VoiceService restoreService = service;
      if (previewOrb[0] != null)
        runOnMainSync(
            () ->
                previewOrb[0].setLevelSource(
                    playing -> restoreService == null ? 0 : restoreService.audioLevel(playing)));
      if (service != null && activity != null) service.attach(activity);
      Bundle result = new Bundle();
      result.putString("error", e.toString());
      if (wake.isHeld()) wake.release();
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private void assertHistoryVisible(MainActivity activity, boolean expected) {
    try {
      for (String name : new String[] {"scroll", "conversationHead", "welcome"}) {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        android.view.View view = (android.view.View) field.get(activity);
        boolean visible = name.equals("welcome") ? !expected : expected;
        if ((view.getVisibility() == android.view.View.VISIBLE) != visible)
          throw new IllegalStateException("历史消息显示错误: " + name + ", expected=" + visible);
      }
      Field rows = MainActivity.class.getDeclaredField("messages");
      rows.setAccessible(true);
      int count = ((android.view.ViewGroup) rows.get(activity)).getChildCount();
      if (expected && count != 100)
        throw new IllegalStateException("历史消息行丢失: " + count);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private void verifyScreenIdle(MainActivity activity, VoiceService.Snapshot standby,
      VoiceService.Snapshot reconnect) {
    try {
      android.content.SharedPreferences prefs = activity.getSharedPreferences("config", 0);
      if (!prefs.getBoolean("autoOff", true)) throw new IllegalStateException("自动熄屏未启用");
      int minutes = prefs.getInt("offMinutes", 5);
      Field idle = MainActivity.class.getDeclaredField("lastInteraction");
      Field black = MainActivity.class.getDeclaredField("black");
      Field tick = MainActivity.class.getDeclaredField("tick");
      java.lang.reflect.Method touch = MainActivity.class.getDeclaredMethod("touch");
      idle.setAccessible(true); black.setAccessible(true); tick.setAccessible(true); touch.setAccessible(true);
      activity.update(standby);
      long since = android.os.SystemClock.elapsedRealtime() - minutes * 60000L - 1;
      idle.setLong(activity, since);
      activity.update(reconnect);
      activity.update(standby);
      if (idle.getLong(activity) != since) throw new IllegalStateException("后台重连重置了熄屏计时");
      Runnable timer = (Runnable) tick.get(activity);
      timer.run();
      // Avoid adding an extra recurring timer during the test.
      Field ui = MainActivity.class.getDeclaredField("ui");
      ui.setAccessible(true);
      ((android.os.Handler) ui.get(activity)).removeCallbacks(timer);
      int flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
      if (!black.getBoolean(activity) || (activity.getWindow().getAttributes().flags & flag) != 0)
        throw new IllegalStateException("到达所选时长后未释放常亮标记");
      activity.update(reconnect);
      if (!black.getBoolean(activity)) throw new IllegalStateException("后台重连唤亮屏幕");
      activity.update(standby);
      touch.invoke(activity);
      if (black.getBoolean(activity)) throw new IllegalStateException("触摸未恢复屏幕");
      if (prefs.getBoolean("keepScreen", true)
          && (activity.getWindow().getAttributes().flags & flag) == 0)
        throw new IllegalStateException("触摸后未恢复窗口亮屏");
      ((android.os.Handler) ui.get(activity)).postDelayed(timer, 1000);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private void assertConversationUi(MainActivity activity, boolean inConversation, boolean canEnd) {
    try {
      Field statusField = MainActivity.class.getDeclaredField("status");
      Field endField = MainActivity.class.getDeclaredField("endButton");
      Field headingField = MainActivity.class.getDeclaredField("conversationTitle");
      statusField.setAccessible(true);
      endField.setAccessible(true);
      headingField.setAccessible(true);
      String status = ((android.widget.TextView) statusField.get(activity)).getText().toString();
      String heading = ((android.widget.TextView) headingField.get(activity)).getText().toString();
      if (!status.contains(inConversation ? "对话中" : "待机")
          || !heading.contains(inConversation ? "对话中" : "记录已保存")
          || ((android.view.View) endField.get(activity)).isEnabled() != canEnd)
        throw new IllegalStateException("对话状态显示不一致: " + status + ", " + heading);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private void assertLastMessageVisible(MainActivity activity) {
    try {
      Field contentField = MainActivity.class.getDeclaredField("messages");
      Field scrollField = MainActivity.class.getDeclaredField("scroll");
      contentField.setAccessible(true);
      scrollField.setAccessible(true);
      android.view.ViewGroup content = (android.view.ViewGroup) contentField.get(activity);
      android.widget.ScrollView viewport = (android.widget.ScrollView) scrollField.get(activity);
      android.view.View last = content.getChildAt(content.getChildCount() - 1);
      int bottom = last.getBottom() - viewport.getScrollY();
      if (bottom <= 0 || last.getTop() - viewport.getScrollY() >= viewport.getHeight())
        throw new IllegalStateException("消息在视口外: bottom=" + bottom + ", scrollY=" + viewport.getScrollY());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}

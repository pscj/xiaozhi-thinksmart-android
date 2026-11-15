package com.justhalfbit.thinksmart;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.FileInputStream;

/** Exercises real window/curtain behavior with separate prefs; never edits user settings. */
public final class SleepDisplayRunner extends Instrumentation {
  private int passed;

  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  @Override public void onStart() {
    Bundle result = new Bundle();
    MainActivity activity = null;
    SharedPreferences original = null;
    SharedPreferences test = getTargetContext().getSharedPreferences("sleep-display-test", Context.MODE_PRIVATE);
    int resultCode = Activity.RESULT_OK;
    final int[] restores = {0};
    try {
      activity = (MainActivity) startActivitySync(
          new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      final MainActivity current = activity;
      original = (SharedPreferences) get(current, "prefs");
      runOnMainSync(() -> {
        try {
          VoiceService service = (VoiceService) get(current, "service");
          if (service != null) service.detach();
          set(current, "prefs", test);
          set(current, "snapshot", null);
          set(current, "active", false);
          set(current, "overlayOpen", false);
          test.edit().clear().putBoolean("keepScreen", true).putInt("brightness", 80)
              .putBoolean("autoOff", true).putInt("offMinutes", 3)
              .putString("sleepStart", "00:00").putString("sleepEnd", "00:00").commit();

          // Still-interactive black curtain: a normal UI tick used to erase ownership
          // at the end of sleep, leaving blackRecheck unable to restore the display.
          test.edit().putBoolean("sleep", true).commit();
          set(current, "lastInteraction", SystemClock.elapsedRealtime() - 180_001);
          tick(current);
          check(Boolean.TRUE.equals(get(current, "blackForSleep")), "睡眠黑屏应记录归属");
          test.edit().putBoolean("sleep", false).commit();
          tick(current);
          assertRestored(current);
          check(((AmbientView) get(current, "ambient")).isShowing(), "睡眠结束应返回待机屏");
          passed++;

          // A black curtain entered before bedtime must also recover in the morning.
          call(current, "setBlack", boolean.class, true);
          check(Boolean.FALSE.equals(get(current, "blackForSleep")), "白天空闲黑屏不应立即自动恢复");
          ((Runnable) get(current, "blackRecheck")).run();
          check(Boolean.TRUE.equals(get(current, "black")), "白天空闲黑屏应保留");
          test.edit().putBoolean("sleep", true).commit();
          ((Runnable) get(current, "blackRecheck")).run();
          check(Boolean.TRUE.equals(get(current, "blackForSleep")), "入睡时应接管已有黑屏");
          test.edit().putBoolean("sleep", false).commit();
          ((Runnable) get(current, "blackRecheck")).run();
          assertRestored(current);
          passed++;

          // Settings can change before the next service snapshot arrives.
          test.edit().putBoolean("sleep", true).commit();
          call(current, "applyBrightness");
          check(Math.abs(current.getWindow().getAttributes().screenBrightness - .1f) < .001f,
              "睡眠时应立即调暗窗口");
          test.edit().putBoolean("sleep", false).commit();
          current.restoreAfterSleep();
          assertRestored(current);
          passed++;

          // A stopped Activity must restore before the first idle tick on restart.
          test.edit().putBoolean("sleep", true).commit();
          call(current, "setBlack", boolean.class, true);
          test.edit().putBoolean("sleep", false).commit();
          call(current, "onStart");
          assertRestored(current);
          check(((AmbientView) get(current, "ambient")).isShowing(), "重新显示时应直接恢复待机屏");
          passed++;

          // The service must notify the UI even when Android reports interactive.
          VoiceService serviceProbe = new VoiceService() {{ attachBaseContext(getTargetContext()); }};
          setService(serviceProbe, "listener", new VoiceService.Listener() {
            public void update(VoiceService.Snapshot value) {}
            public void showVolume(int volume) {}
            public void restoreAfterSleep() { restores[0]++; }
          });
          Method wake = VoiceService.class.getDeclaredMethod("wakeScreenAfterSleep");
          wake.setAccessible(true);
          wake.invoke(serviceProbe);
        } catch (Exception e) { throw new RuntimeException(e); }
      });
      waitForIdleSync();
      check(restores[0] == 1, "系统仍亮屏时也必须通知界面移除遮罩");
      passed++;

      // Verify the detached-service path with the display physically off, without
      // changing the clock, system timeout, real schedule, or user preferences.
      runOnMainSync(() -> {
        try {
          test.edit().putBoolean("sleep", true).commit();
          call(current, "setBlack", boolean.class, true);
          test.edit().putBoolean("sleep", false).commit();
          ((Handler) get(current, "ui")).removeCallbacksAndMessages(null);
        } catch (Exception e) { throw new RuntimeException(e); }
      });
      try (ParcelFileDescriptor output = getUiAutomation().executeShellCommand("input keyevent 223");
          FileInputStream stream = new FileInputStream(output.getFileDescriptor())) {
        while (stream.read() != -1) {}
      }
      PowerManager pm = (PowerManager) getTargetContext().getSystemService(Context.POWER_SERVICE);
      long until = SystemClock.elapsedRealtime() + 5000;
      while (pm.isInteractive() && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
      check(!pm.isInteractive(), "真机必须先进入熄屏状态");
      VoiceService serviceProbe = new VoiceService() {{ attachBaseContext(getTargetContext()); }};
      Method wake = VoiceService.class.getDeclaredMethod("wakeScreenAfterSleep");
      wake.setAccessible(true);
      wake.invoke(serviceProbe);
      until = SystemClock.elapsedRealtime() + 5000;
      while (!pm.isInteractive() && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
      check(pm.isInteractive(), "睡眠结束事件应唤醒实际屏幕");
      waitForIdleSync();
      runOnMainSync(() -> {
        try { assertRestored(current); }
        catch (Exception e) { throw new RuntimeException(e); }
      });
      passed++;
    } catch (Throwable e) {
      resultCode = Activity.RESULT_CANCELED;
      result.putString("error", e.toString());
    } finally {
      if (activity != null && original != null) {
        final MainActivity current = activity;
        final SharedPreferences saved = original;
        runOnMainSync(() -> {
          try {
            ((Handler) get(current, "ui")).removeCallbacksAndMessages(null);
            set(current, "prefs", saved);
            current.restoreAfterSleep();
            VoiceService service = (VoiceService) get(current, "service");
            if (service != null) service.attach(current);
          } catch (Exception e) { throw new RuntimeException(e); }
        });
      }
      test.edit().clear().commit();
    }
    result.putInt("passed", passed);
    finish(resultCode, result);
  }

  private static void tick(MainActivity activity) throws Exception {
    ((Runnable) get(activity, "tick")).run();
    ((Handler) get(activity, "ui")).removeCallbacks((Runnable) get(activity, "tick"));
  }

  private static void assertRestored(MainActivity activity) throws Exception {
    check(Boolean.FALSE.equals(get(activity, "black")), "睡眠结束后不应保留黑屏");
    check(((View) get(activity, "curtain")).getVisibility() == View.GONE, "应移除黑色遮罩");
    WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
    check(Math.abs(attrs.screenBrightness - .8f) < .001f, "应恢复用户设置的80%亮度");
    check((attrs.flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0, "应恢复常亮设置");
  }

  private static Object get(Object target, String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void setService(VoiceService target, String name, Object value) throws Exception {
    Field field = VoiceService.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void call(MainActivity activity, String name) throws Exception {
    Method method = MainActivity.class.getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(activity);
  }

  private static void call(MainActivity activity, String name, Class<?> type, Object value) throws Exception {
    Method method = MainActivity.class.getDeclaredMethod(name, type);
    method.setAccessible(true);
    method.invoke(activity, value);
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}

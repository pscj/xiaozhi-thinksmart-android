package com.justhalfbit.thinksmart;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;

/** Real HTTP/download/decode/rotation checks with isolated files; never changes user settings. */
public final class PhotoFrameRunner extends Instrumentation {
  private AmbientView view;
  private int passed;
  private boolean preview;

  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    preview = "true".equals(args.getString("preview"));
    start();
  }

  @Override public void onStart() {
    if (preview) {
      captureDesignPreview();
      return;
    }
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    File directory = new File(getTargetContext().getCacheDir(), "photo-frame-test-" + System.nanoTime());
    try (PhotoServer server = new PhotoServer()) {
      byte[] red = image(Color.RED), blue = image(Color.BLUE);
      Plan first = server.add(200, red, false);
      Plan late = server.add(200, blue, false);
      server.add(503, new byte[] {1}, true);
      Plan broken = server.add(200, new byte[] {1, 2, 3}, true);
      Plan ready = server.add(200, red, true);
      Plan cancelled = server.add(200, blue, false);
      RemotePhotoRepository source = new RemotePhotoRepository(directory,
          "http://127.0.0.1:" + server.port() + "/api/images/random",
          RemotePhotoRepository.createHttpClient());
      main(() -> {
        view = new AmbientView(getTargetContext());
        set(view, "remotePhotos", source);
        view.preparePhotos();
      });
      check(first.requested.await(5, TimeUnit.SECONDS), "未进入相框时就应开始后台准备照片");
      main(() -> check(!view.isShowing(), "预下载不应强制进入相框"));
      passed++;
      main(() -> {
        view.show(AmbientView.Mode.PHOTO);
      });
      await(() -> read("fallbackBitmap") != null, "首张慢下载时应显示临时风景背景");
      check(front() == null, "临时背景不能冒充已下载的照片");
      passed++;
      first.release.countDown();
      await(() -> front() != null, "首张照片应完成解码和展示");
      Bitmap original = front();
      check(late.requested.await(5, TimeUnit.SECONDS), "展示当前图时就应预下载下一张");
      check(AmbientView.PHOTO_MS == 300_000L, "轮换间隔必须为五分钟");
      check(front() == original, "后台请求不得清空当前照片");
      passed++;

      main(() -> {
        set(view, "photoDueAt", SystemClock.elapsedRealtime() - 1);
        call(view, "advancePhotoIfReady");
      });
      check(front() == original, "到期但未下载完成时必须保留原图");
      late.release.countDown();
      await(() -> front() != original, "过期后下载完成应自动切换");
      Bitmap current = front();
      check(current.getPixel(0, 0) == Color.BLUE, "应展示已完整下载的新图");
      passed++;

      await(() -> !loading(), "HTTP 错误应结束本次请求");
      check(front() == current, "HTTP 错误必须保留当前图");
      check(source.cachedPhoto().isFile(), "展示成功后应保留磁盘缓存");
      passed++;

      main(() -> call(view, "prefetchPhoto"));
      check(broken.requested.await(5, TimeUnit.SECONDS), "应允许失败后重试");
      await(() -> !loading(), "坏图应结束本次请求");
      check(front() == current, "无法解码的图片不得替换当前图");
      passed++;

      main(() -> call(view, "prefetchPhoto"));
      check(ready.requested.await(5, TimeUnit.SECONDS), "应提前获取待展示照片");
      await(() -> pending() != null, "成功下载的新图应等待轮换时间");
      check(front() == current, "提前下载完成不得提前轮换");
      File orphan = new File(directory, "download-abandoned.tmp");
      try (FileOutputStream output = new FileOutputStream(orphan)) { output.write(1); }
      source.cleanup();
      check(!orphan.exists(), "清理应删除残留文件");
      main(() -> {
        File next = (File) get(view, "pendingPhotoFile");
        check(next.isFile(), "清理不得删除等待展示的图片");
        set(view, "photoDueAt", SystemClock.elapsedRealtime() - 1);
        call(view, "advancePhotoIfReady");
      });
      check(front().getPixel(0, 0) == Color.RED, "到期应切换已预下载的图片");
      passed++;

      check(cancelled.requested.await(5, TimeUnit.SECONDS), "新一轮应继续后台预下载");
      final Call[] request = new Call[1];
      main(() -> {
        request[0] = (Call) get(view, "photoCall");
        view.hide();
      });
      check(!request[0].isCanceled(), "暂时退出相框不得取消慢下载");
      cancelled.release.countDown();
      await(() -> pending() != null, "隐藏相框后下载仍应完成并保留待展示结果");
      main(() -> check(!view.isShowing(), "后台下载完成不得强制显示相框"));
      passed++;

      server.add(200, blue, false);
      main(() -> {
        view.onDetachedFromWindow();
        view = new AmbientView(getTargetContext());
        set(view, "remotePhotos", source);
        view.show(AmbientView.Mode.PHOTO);
      });
      await(() -> front() != null, "重新进入应从磁盘恢复最近照片");
      check(front().getPixel(0, 0) == Color.RED, "网络未完成时应展示最近缓存");
      passed++;
      final Call[] detached = new Call[1];
      main(() -> {
        detached[0] = (Call) get(view, "photoCall");
        view.onDetachedFromWindow();
      });
      check(detached[0] != null && detached[0].isCanceled(), "销毁页面时仍应取消请求并释放资源");
      passed++;

      File rotated = new File(directory, "orientation.jpg");
      Bitmap fixture = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888);
      try (FileOutputStream output = new FileOutputStream(rotated)) {
        fixture.compress(Bitmap.CompressFormat.JPEG, 90, output);
      }
      fixture.recycle();
      ExifInterface exif = new ExifInterface(rotated.getPath());
      exif.setAttribute(ExifInterface.TAG_ORIENTATION, "6");
      exif.saveAttributes();
      Method decode = AmbientView.class.getDeclaredMethod("decodeSampled", File.class);
      decode.setAccessible(true);
      Bitmap decoded = (Bitmap) decode.invoke(null, rotated);
      check(decoded.getWidth() == 16 && decoded.getHeight() == 32, "应按 EXIF 方向旋转原图");
      decoded.recycle();
      passed++;

      File sample = new File(getTargetContext().getCacheDir(), "photo-frame-api-sample");
      if (sample.isFile()) {
        check(RemotePhotoRepository.looksComplete(sample), "完整 API 原图应通过完整性检查");
        Bitmap originalPhoto = (Bitmap) decode.invoke(null, sample);
        check(originalPhoto != null, "真实 API 原图应能在 Android 设备上解码");
        check(Math.max(originalPhoto.getWidth(), originalPhoto.getHeight()) <= 1280,
            "真实大图应采样到安全尺寸");
        check(originalPhoto.getAllocationByteCount() <= 1280 * 1280 * 4,
            "大图必须先重采样再分配显示位图");
        result.putString("sampled_dimensions", originalPhoto.getWidth() + "x" + originalPhoto.getHeight());
        result.putInt("sampled_bytes", originalPhoto.getAllocationByteCount());
        originalPhoto.recycle();
        sample.delete();
        passed++;
      }
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      result.putString("error", android.util.Log.getStackTraceString(error));
    } finally {
      if (view != null) main(() -> view.onDetachedFromWindow());
      File[] files = directory.listFiles();
      if (files != null) for (File file : files) file.delete();
      directory.delete();
    }
    result.putInt("passed", passed);
    finish(code, result);
  }

  /** Render the actual photo UI with a bundled fixture without changing the selected standby mode. */
  private void captureDesignPreview() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    FrameLayout[] parent = new FrameLayout[1];
    try {
      MainActivity activity = (MainActivity) startActivitySync(new Intent(getTargetContext(),
          MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Field frame = MainActivity.class.getDeclaredField("frame");
      frame.setAccessible(true);
      parent[0] = (FrameLayout) frame.get(activity);
      Bitmap bitmap;
      try (java.io.InputStream input = getTargetContext().getAssets().open("ambient_bg/frame-lake.jpg")) {
        bitmap = BitmapFactory.decodeStream(input);
      }
      main(() -> {
        view = new AmbientView(activity);
        set(view, "frontBitmap", bitmap);
        set(view, "photoDueAt", SystemClock.elapsedRealtime() + AmbientView.PHOTO_MS);
        ((ImageView) get(view, "photoA")).setImageBitmap(bitmap);
        parent[0].addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.show(AmbientView.Mode.PHOTO);
      });
      waitForIdleSync();
      Thread.sleep(1000);
      File file = new File(getTargetContext().getExternalFilesDir(null), "photo-design-d.png");
      Bitmap screenshot = getUiAutomation().takeScreenshot();
      if (screenshot == null) throw new IllegalStateException("无法读取相框截图");
      try (FileOutputStream output = new FileOutputStream(file)) {
        screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
      }
      screenshot.recycle();
      result.putString("screenshot", file.getAbsolutePath());
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      result.putString("error", android.util.Log.getStackTraceString(error));
    } finally {
      if (view != null && parent[0] != null) main(() -> parent[0].removeView(view));
    }
    finish(code, result);
  }

  private Bitmap front() throws Exception { return (Bitmap) read("frontBitmap"); }
  private Bitmap pending() throws Exception { return (Bitmap) read("pendingPhoto"); }
  private boolean loading() throws Exception { return Boolean.TRUE.equals(read("photoLoading")); }
  private Object read(String name) throws Exception {
    Object[] value = new Object[1];
    main(() -> value[0] = get(view, name));
    return value[0];
  }

  private interface Action { void run() throws Exception; }
  private interface Condition { boolean done() throws Exception; }
  private void main(Action action) {
    Throwable[] failure = new Throwable[1];
    runOnMainSync(() -> {
      try { action.run(); } catch (Throwable error) { failure[0] = error; }
    });
    if (failure[0] != null) throw new IllegalStateException("Photo test UI action failed", failure[0]);
  }
  private void await(Condition condition, String message) throws Exception {
    long until = SystemClock.elapsedRealtime() + 5000;
    while (!condition.done() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
    check(condition.done(), message);
  }
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  private static Object get(Object target, String name) throws Exception {
    Field field = AmbientView.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }
  private static void set(Object target, String name, Object value) throws Exception {
    Field field = AmbientView.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
  private static void call(Object target, String name) throws Exception {
    Method method = AmbientView.class.getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(target);
  }
  private static byte[] image(int color) {
    Bitmap bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(color);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    bitmap.recycle();
    return output.toByteArray();
  }

  private static final class Plan {
    final int status;
    final byte[] body;
    final CountDownLatch requested = new CountDownLatch(1);
    final CountDownLatch release;
    Plan(int status, byte[] body, boolean ready) {
      this.status = status;
      this.body = body;
      release = new CountDownLatch(ready ? 0 : 1);
    }
  }

  private static final class PhotoServer implements AutoCloseable {
    final ServerSocket socket = new ServerSocket(0);
    final LinkedBlockingQueue<Plan> queue = new LinkedBlockingQueue<>();
    final List<Plan> plans = new ArrayList<>();
    PhotoServer() throws Exception {
      new Thread(() -> {
        while (!socket.isClosed()) {
          try {
            Socket client = socket.accept();
            new Thread(() -> serve(client), "photo-test-response").start();
          } catch (Exception ignored) { return; }
        }
      }, "photo-test-server").start();
    }
    int port() { return socket.getLocalPort(); }
    Plan add(int status, byte[] body, boolean ready) {
      Plan plan = new Plan(status, body, ready);
      plans.add(plan);
      queue.add(plan);
      return plan;
    }
    void serve(Socket client) {
      try (Socket connection = client) {
        connection.setSoTimeout(5000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(),
            StandardCharsets.US_ASCII));
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {}
        Plan plan = queue.poll(5, TimeUnit.SECONDS);
        if (plan == null) return;
        plan.requested.countDown();
        if (!plan.release.await(10, TimeUnit.SECONDS)) return;
        OutputStream output = connection.getOutputStream();
        output.write(("HTTP/1.1 " + plan.status + " Result\r\nContent-Type: image/png\r\n"
            + "Content-Length: " + plan.body.length + "\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        output.write(plan.body);
        output.flush();
      } catch (Exception ignored) { /* Cancellation intentionally closes sockets. */ }
    }
    @Override public void close() throws Exception {
      socket.close();
      for (Plan plan : plans) plan.release.countDown();
    }
  }
}

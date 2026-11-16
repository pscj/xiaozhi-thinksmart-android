package com.justhalfbit.thinksmart;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Streams one random original to disk. Only the view may promote a decoded image to cache. */
final class RemotePhotoRepository {
  interface Progress {
    void update(long received, long total);
  }
  static final String DEFAULT_URL = "http://192.168.1.102:8000/api/images/random";
  static final long TIMEOUT_SECONDS = 120;
  static final long CLEANUP_INTERVAL_HOURS = 6;
  private static RemotePhotoRepository applicationPhotos;
  private final File directory;
  private final String url;
  private final OkHttpClient http;
  private final Set<File> inUse = new HashSet<>();

  /** One cleaner for the app process, independent of the photo screen's visibility. */
  static synchronized RemotePhotoRepository forApp(File directory) {
    if (applicationPhotos == null) {
      applicationPhotos = new RemotePhotoRepository(directory);
      Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "photo-cache-cleanup");
        thread.setDaemon(true);
        return thread;
      }).scheduleWithFixedDelay(applicationPhotos::cleanup, 0,
          CLEANUP_INTERVAL_HOURS, TimeUnit.HOURS);
    }
    return applicationPhotos;
  }

  RemotePhotoRepository(File directory) {
    this(directory, DEFAULT_URL, createHttpClient());
  }

  static OkHttpClient createHttpClient() {
    // Large originals may legitimately take longer than a whole slideshow slot.
    // Bound connection/stalls, but do not cancel a download that keeps making progress.
    return new OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build();
  }

  RemotePhotoRepository(File directory, String url, OkHttpClient http) {
    this.directory = directory;
    this.url = url;
    this.http = http;
  }

  File cachedPhoto() {
    return new File(directory, "current-image");
  }

  Call newCall() {
    HttpUrl imageUrl = HttpUrl.parse(url).newBuilder()
        .setQueryParameter("width", "1280")
        .setQueryParameter("height", "800")
        .build();
    return http.newCall(new Request.Builder().url(imageUrl)
        .header("Accept", "image/*").header("Cache-Control", "no-cache").build());
  }

  /** Returns a complete temporary file; HTTP errors, timeouts and partial bodies leave no file. */
  File download(Call call) throws IOException {
    return download(call, null);
  }

  File download(Call call, Progress progress) throws IOException {
    File temporary = createTemporary();
    boolean complete = false;
    try {
      try (Response response = call.execute()) {
        ResponseBody body = response.body();
        if (!response.isSuccessful() || body == null || body.contentType() == null
            || !"image".equals(body.contentType().type())) {
          throw new IOException("Photo HTTP " + response.code() + ", type=" + bodyType(body));
        }
        long received = 0;
        long lastProgress = System.nanoTime();
        if (progress != null) progress.update(0, body.contentLength());
        try (InputStream input = body.byteStream();
            FileOutputStream output = new FileOutputStream(temporary)) {
          byte[] buffer = new byte[32 * 1024];
          int count;
          while ((count = input.read(buffer)) != -1) {
            if (call.isCanceled()) throw new IOException("Photo request cancelled");
            output.write(buffer, 0, count);
            received += count;
            long now = System.nanoTime();
            if (progress != null && now - lastProgress >= TimeUnit.SECONDS.toNanos(1)) {
              progress.update(received, body.contentLength());
              lastProgress = now;
            }
          }
        }
        if (received == 0 || (body.contentLength() >= 0 && received != body.contentLength())
            || call.isCanceled()) {
          throw new IOException("Incomplete photo response");
        }
        if (progress != null) progress.update(received, body.contentLength());
      }
      complete = true;
      return temporary;
    } finally {
      if (!complete) discard(temporary);
    }
  }

  private static String bodyType(ResponseBody body) {
    return body == null || body.contentType() == null ? "none" : body.contentType().toString();
  }

  private synchronized File createTemporary() throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
      throw new IOException("Cannot create photo cache");
    }
    File temporary = File.createTempFile("download-", ".tmp", directory);
    inUse.add(temporary);
    return temporary;
  }

  /** Rename in the same directory preserves the previous cache if promotion fails. */
  synchronized boolean promote(File decodedPhoto) {
    if (!decodedPhoto.renameTo(cachedPhoto())) return false;
    inUse.remove(decodedPhoto);
    return true;
  }

  synchronized void discard(File file) {
    file.delete();
    inUse.remove(file);
  }

  /** Remove orphaned downloads, keeping the current cache and every active/prefetched file. */
  synchronized void cleanup() {
    File[] files = directory.listFiles();
    if (files == null) return;
    for (File file : files) {
      String name = file.getName();
      if (name.startsWith("download-") && name.endsWith(".tmp") && !inUse.contains(file)) {
        file.delete();
      }
    }
  }

  /** The API has no Content-Length: reject truncated common formats before permissive decoding. */
  static boolean looksComplete(File file) {
    try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
      long size = input.length();
      if (size < 12) return false;
      int first = input.readInt();
      if ((first >>> 16) == 0xffd8) {
        // Camera originals may pad the file with zero/fill bytes after JPEG's EOI.
        byte[] tail = new byte[8192];
        long end = size;
        while (end > 2) {
          long start = Math.max(2, end - tail.length);
          int count = (int) (end - start);
          input.seek(start);
          input.readFully(tail, 0, count);
          for (int i = count - 1; i >= 0; i--) {
            int value = tail[i] & 0xff;
            if (value == 0 || value == 0xff) continue;
            if (value != 0xd9) return false;
            input.seek(start + i - 1);
            return input.readUnsignedByte() == 0xff;
          }
          end = start;
        }
        return false;
      }
      if (first == 0x89504e47) {
        input.seek(size - 12);
        return input.readInt() == 0 && input.readInt() == 0x49454e44
            && input.readInt() == 0xae426082;
      }
      if (first == 0x52494646) {
        long riffLength = Integer.reverseBytes(input.readInt()) & 0xffffffffL;
        return input.readInt() == 0x57454250 && riffLength + 8 == size;
      }
      // Other formats are validated by the platform decoder.
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}

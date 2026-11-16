package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RemotePhotoRepositoryTest {
  @Rule public TemporaryFolder folder = new TemporaryFolder();
  private MockWebServer server;
  private RemotePhotoRepository photos;
  private File directory;

  @Before public void setup() throws Exception {
    server = new MockWebServer();
    server.start();
    directory = folder.newFolder();
    photos = new RemotePhotoRepository(directory, server.url("/api/images/random").toString(),
        new OkHttpClient.Builder().callTimeout(250, TimeUnit.MILLISECONDS).build());
    Files.write(photos.cachedPhoto().toPath(), "previous-photo".getBytes(StandardCharsets.UTF_8));
  }

  @After public void close() throws Exception { server.shutdown(); }

  @Test public void completedDownloadDoesNotReplaceCurrentPhotoBeforeDisplay() throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "image/jpeg").setBody("next-photo"));
    File next = photos.download(photos.newCall());
    assertEquals("next-photo", read(next));
    assertEquals("previous-photo", read(photos.cachedPhoto()));
    assertEquals("/api/images/random?width=1280&height=800", server.takeRequest().getPath());
    assertTrue(photos.promote(next));
    assertEquals("next-photo", read(photos.cachedPhoto()));
    assertFalse(next.exists());
  }

  @Test public void supportsTheApiCloseDelimitedBodyWithoutContentLength() throws Exception {
    server.enqueue(new MockResponse().setBody("original-bytes")
        .removeHeader("Content-Length").setHeader("Content-Type", "image/webp")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
    File next = photos.download(photos.newCall());
    assertEquals("original-bytes", read(next));
    assertEquals("previous-photo", read(photos.cachedPhoto()));
  }

  @Test public void reportsReceivedBytesEvenWhenTheApiHasNoContentLength() throws Exception {
    server.enqueue(new MockResponse().setBody("original-bytes")
        .removeHeader("Content-Length").setHeader("Content-Type", "image/webp")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
    long[] progress = {-1, 0};
    photos.download(photos.newCall(), (received, total) -> {
      progress[0] = received;
      progress[1] = total;
    });
    assertEquals(14, progress[0]);
    assertEquals(-1, progress[1]);
  }

  @Test public void httpErrorsAndNonImageBodiesPreserveCurrentAndRemoveTemporaryFiles() throws Exception {
    for (MockResponse response : new MockResponse[] {
        new MockResponse().setResponseCode(503).setHeader("Content-Type", "image/jpeg").setBody("error"),
        new MockResponse().setHeader("Content-Type", "application/json").setBody("{error:true}"),
        new MockResponse().setHeader("Content-Type", "image/png").setBody("")}) {
      server.enqueue(response);
      assertDownloadFails(photos.newCall());
    }
  }

  @Test public void interruptedBodyIsNotPublishedAsACompletedImage() throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "image/jpeg")
        .setBody("a fairly long image body").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
    assertDownloadFails(photos.newCall());
  }

  @Test public void totalDownloadTimeoutPreservesCurrentAndRemovesPartialFile() throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "image/jpeg")
        .setBody("slow-photo").throttleBody(1, 100, TimeUnit.MILLISECONDS));
    assertDownloadFails(photos.newCall());
  }

  @Test public void cancelledRequestCannotReplaceCurrentPhoto() throws Exception {
    Call call = photos.newCall();
    call.cancel();
    assertDownloadFails(call);
    assertEquals(0, server.getRequestCount());
  }

  @Test public void productionAllowsSlowProgressButBoundsConnectionAndStallsAt120Seconds() {
    OkHttpClient http = RemotePhotoRepository.createHttpClient();
    assertEquals(120_000, http.connectTimeoutMillis());
    assertEquals(120_000, http.readTimeoutMillis());
    assertEquals(0, http.callTimeoutMillis());
  }

  @Test public void resizeParametersAreSentOnceAndOtherParametersArePreserved() {
    RemotePhotoRepository resized = new RemotePhotoRepository(directory,
        server.url("/api/images/random?orientation=portrait&width=600&height=400").toString(),
        new OkHttpClient());
    okhttp3.HttpUrl request = resized.newCall().request().url();
    assertEquals("1280", request.queryParameter("width"));
    assertEquals("800", request.queryParameter("height"));
    assertEquals(1, request.queryParameterValues("width").size());
    assertEquals(1, request.queryParameterValues("height").size());
    assertEquals("portrait", request.queryParameter("orientation"));
  }

  @Test public void cleanupDeletesOrphansButKeepsCurrentAndPrefetchedImage() throws Exception {
    File orphan = new File(directory, "download-interrupted.tmp");
    Files.write(orphan.toPath(), new byte[] {1, 2, 3});
    File unrelated = new File(directory, "other-file");
    Files.write(unrelated.toPath(), new byte[] {4});
    server.enqueue(new MockResponse().setHeader("Content-Type", "image/png").setBody("next-photo"));
    File next = photos.download(photos.newCall());
    photos.cleanup();
    assertFalse(orphan.exists());
    assertTrue(unrelated.exists());
    assertEquals("previous-photo", read(photos.cachedPhoto()));
    assertEquals("next-photo", read(next));
    assertTrue(photos.promote(next));
    photos.cleanup();
    assertEquals("next-photo", read(photos.cachedPhoto()));
  }

  @Test public void cleanupDoesNotDeleteAnImageBeingDownloaded() throws Exception {
    photos = new RemotePhotoRepository(directory, server.url("/").toString(),
        new OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build());
    server.enqueue(new MockResponse().setHeader("Content-Type", "image/jpeg")
        .setBody("next-photo").setBodyDelay(100, TimeUnit.MILLISECONDS));
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<File> download = worker.submit(() -> photos.download(photos.newCall()));
      assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
      photos.cleanup();
      File next = download.get(2, TimeUnit.SECONDS);
      assertEquals("next-photo", read(next));
      photos.discard(next);
      assertFalse(next.exists());
      assertEquals("previous-photo", read(photos.cachedPhoto()));
    } finally {
      worker.shutdownNow();
    }
  }

  @Test public void rejectsJpegPngAndWebpFilesTruncatedWithoutAnHttpContentLength() throws Exception {
    byte[][] samples = {
        {(byte) 0xff, (byte) 0xd8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xd9},
        {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10,
            0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, (byte) 0xae, 0x42, 0x60, (byte) 0x82},
        {0x52, 0x49, 0x46, 0x46, 6, 0, 0, 0, 0x57, 0x45, 0x42, 0x50, 0, 0}};
    File sample = new File(directory, "sample");
    for (byte[] bytes : samples) {
      Files.write(sample.toPath(), bytes);
      assertTrue(RemotePhotoRepository.looksComplete(sample));
      Files.write(sample.toPath(), java.util.Arrays.copyOf(bytes, bytes.length - 1));
      assertFalse(RemotePhotoRepository.looksComplete(sample));
    }
  }

  @Test public void acceptsCameraJpegWithTrailingPaddingWithoutAcceptingATruncatedScan() throws Exception {
    byte[] padded = new byte[20_000];
    padded[0] = (byte) 0xff;
    padded[1] = (byte) 0xd8;
    padded[4999] = (byte) 0xff;
    padded[5000] = (byte) 0xd9;
    File sample = new File(directory, "camera-jpeg");
    Files.write(sample.toPath(), padded);
    assertTrue(RemotePhotoRepository.looksComplete(sample));
    padded[5000] = 0x11;
    Files.write(sample.toPath(), padded);
    assertFalse(RemotePhotoRepository.looksComplete(sample));
  }

  private void assertDownloadFails(Call call) throws Exception {
    try {
      photos.download(call);
      fail("Failed download must not be published");
    } catch (IOException expected) {
      assertEquals("previous-photo", read(photos.cachedPhoto()));
      assertArrayEquals(new String[] {"current-image"}, directory.list());
    }
  }

  private static String read(File file) throws Exception {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }
}

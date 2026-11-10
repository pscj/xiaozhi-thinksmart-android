package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;

public class ManagementClientTest {
  private MockWebServer server;
  private ManagementClient client;

  @Before
  public void setup() throws Exception {
    server = new MockWebServer();
    server.start();
    client = new ManagementClient(server.url("/bridge/").toString(), "separate-token");
  }

  @After
  public void close() throws Exception {
    client.close();
    server.shutdown();
  }

  private int query(MockResponse response) throws Exception {
    server.enqueue(response);
    CountDownLatch done = new CountDownLatch(1);
    AtomicInteger code = new AtomicInteger(-1);
    client.get(
        "kiosk/llm",
        "device & 1",
        (data, error, status) -> {
          code.set(status);
          done.countDown();
        });
    assertTrue(done.await(4, TimeUnit.SECONDS));
    return code.get();
  }

  @Test
  public void queryEncodesDeviceAndUsesOnlyManagementCredential() throws Exception {
    assertEquals(200, query(new MockResponse().setBody("{options:[]}")));
    RecordedRequest request = server.takeRequest();
    assertEquals("/bridge/kiosk/llm?device=device%20%26%201", request.getPath());
    assertEquals("Bearer separate-token", request.getHeader("Authorization"));
  }

  @Test
  public void forbiddenAndUnsupportedHaveSpecificErrors() throws Exception {
    for (int status : new int[] {401, 403, 501})
      assertEquals(status, query(new MockResponse().setResponseCode(status)));
  }

  @Test
  public void redirectIsNotFollowedAndMalformedJsonFails() throws Exception {
    assertEquals(
        302,
        query(new MockResponse().setResponseCode(302).setHeader("Location", server.url("/other"))));
    assertEquals(0, query(new MockResponse().setBody("<html>not json</html>")));
    assertEquals(2, server.getRequestCount());
  }

  @Test
  public void timeoutReturnsOnce() throws Exception {
    client.close();
    client =
        new ManagementClient(
            server.url("/").toString(),
            "",
            new OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build());
    assertEquals(0, query(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)));
  }

  @Test
  public void reportsPostCarriesDeviceAndQuietWithoutChangingWireContract() throws Exception {
    server.enqueue(new MockResponse().setBody("{ok:true,count:2,playing:false}"));
    CountDownLatch done = new CountDownLatch(1);
    client.post(
        "kiosk/reports",
        new JSONObject().put("device", "d1").put("quiet", true).put("play", false),
        (d, e, s) -> done.countDown());
    assertTrue(done.await(3, TimeUnit.SECONDS));
    RecordedRequest request = server.takeRequest();
    JSONObject data = new JSONObject(request.getBody().readUtf8());
    assertEquals("POST", request.getMethod());
    assertTrue(data.getBoolean("quiet"));
    assertFalse(data.getBoolean("play"));
  }
}

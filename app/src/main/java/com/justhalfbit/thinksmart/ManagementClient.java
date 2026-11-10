package com.justhalfbit.thinksmart;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

/** HTTP transport only. The service owns request generations and state transitions. */
final class ManagementClient implements AutoCloseable {
  interface Result {
    void done(JSONObject data, String error, int status);
  }

  private final OkHttpClient http;
  private final HttpUrl base;
  private final String token;

  ManagementClient(String address, String token) {
    this(
        address,
        token,
        new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build());
  }

  ManagementClient(String address, String token, OkHttpClient http) {
    this.http = http;
    this.base = HttpUrl.parse(address.replaceAll("/+$", "") + "/");
    this.token = token;
  }

  boolean configured() {
    return base != null;
  }

  void get(String path, String device, Result result) {
    if (base == null) {
      result.done(null, "请先在设置中填写服务器 IP", 0);
      return;
    }
    execute(
        new Request.Builder()
            .url(
                base.newBuilder()
                    .addPathSegments(path)
                    .addQueryParameter("device", device)
                    .build()),
        result,
        false);
  }

  void post(String path, JSONObject body, Result result) {
    postBody(
        path,
        RequestBody.create(MediaType.parse("application/json"), body.toString()),
        result,
        false);
  }

  void verify(byte[] pcm, Result result) {
    postBody(
        "kiosk/asr",
        RequestBody.create(MediaType.parse("application/octet-stream"), pcm),
        result,
        true);
  }

  private void postBody(String path, RequestBody body, Result result, boolean fast) {
    if (base == null) {
      result.done(null, "请先在设置中填写服务器 IP", 0);
      return;
    }
    execute(
        new Request.Builder().url(base.newBuilder().addPathSegments(path).build()).post(body),
        result,
        fast);
  }

  private void execute(Request.Builder builder, Result result, boolean fast) {
    if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
    OkHttpClient transport =
        fast ? http.newBuilder().callTimeout(2, TimeUnit.SECONDS).build() : http;
    transport
        .newCall(builder.build())
        .enqueue(
            new Callback() {
              public void onFailure(Call c, IOException e) {
                result.done(null, "管理接口连接失败，请检查地址和网络", 0);
              }

              public void onResponse(Call c, Response response) {
                JSONObject data = null;
                String error = "";
                int code = response.code();
                try (Response r = response) {
                  if (!r.isSuccessful()) {
                    error =
                        code == 403
                            ? "管理接口拒绝访问，请检查服务器权限配置"
                            : code == 401
                                ? "管理接口认证失败"
                                : code == 501 ? "服务器不支持唤醒复核" : "管理接口 HTTP " + code;
                  } else {
                    data = new JSONObject(r.body().string());
                    if (data.has("ok") && !data.optBoolean("ok")) {
                      error = data.optString("error", "服务器操作失败");
                      data = null;
                    }
                  }
                } catch (Exception e) {
                  data = null;
                  error = "管理接口返回格式无效";
                  code = 0;
                }
                result.done(data, error, code);
              }
            });
  }

  public void close() {
    http.dispatcher().cancelAll();
    http.connectionPool().evictAll();
  }
}

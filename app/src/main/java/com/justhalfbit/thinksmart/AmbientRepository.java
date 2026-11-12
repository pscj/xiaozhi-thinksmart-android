package com.justhalfbit.thinksmart;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Where the ambient screen's data comes from.
 *
 * <p>Weather: GET {hub}/kiosk/weather (no parameters), served by xiaozhi-hub-server from its
 * configured weather plugin. The response is
 * {@code {"ok": true, "weather": {"location": {"name": …}, "current": {"summary": …,
 * "details": …}, "forecast": [{"text": …, "temp_max": …, "temp_min": …}, …], "source": …}}};
 * the hub decides the city, the client never hardcodes one. The endpoint is rate-limited, so
 * this client calls it at most once per {@link #WEATHER_MIN_INTERVAL_MS}: the last good
 * payload is kept verbatim in a prefs cache together with the client fetch time, fresh cache
 * is served without any request, and a failed fetch falls back to the cache however old (with
 * the next attempt deferred a full interval). Nothing is ever invented: with no cache the
 * caller gets null and the UI shows a neutral "unavailable" line instead of numbers.
 *
 * <p>News: GET {hub}/news.json, shaped
 * {@code {"date": "2026-11-12", "items": [{"category": "模型", "title": "…", "summary": "…"}]}}
 *
 * <p>TODO(news-source): xiaozhi-hub-server does not serve /news.json yet. The daily AI-news
 * summary pipeline needs to publish that file (same JSON shape) on the hub's HTTP port; until
 * then {@link #fetchNews} falls back to {@link #FALLBACK_NEWS} — four generic category lines
 * carried over from the web prototype, deliberately not dressed up as real headlines. The UI
 * labels the fallback as 示例 so nobody mistakes it for today's news.
 *
 * <p>Fetches run on a single background thread; callbacks are delivered on the main thread. A
 * null weather result means "no data at all" (fetch failed and no cache exists).
 */
final class AmbientRepository {

  interface WeatherCallback {
    void done(AmbientData.WeatherNow weather);
  }

  interface NewsCallback {
    void done(AmbientData.NewsFeed feed);
  }

  private final SharedPreferences prefs;
  private final Supplier<String> hubHttpBase;
  private final OkHttpClient http =
      new OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build();
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());

  AmbientRepository(Context context, Supplier<String> hubHttpBase) {
    this.prefs =
        context.getApplicationContext().getSharedPreferences("ambient-cache", Context.MODE_PRIVATE);
    this.hubHttpBase = hubHttpBase;
  }

  private JSONObject getJson(String url) {
    try {
      Request req = new Request.Builder().url(url).build();
      try (Response resp = http.newCall(req).execute()) {
        if (!resp.isSuccessful() || resp.body() == null) return null;
        return new JSONObject(resp.body().string());
      }
    } catch (Exception e) {
      return null;
    }
  }

  // ---- weather ----

  void fetchWeather(final WeatherCallback callback) {
    io.execute(
        () -> {
          final AmbientData.WeatherNow result = fetchWeatherSync();
          main.post(() -> callback.done(result));
        });
  }

  private AmbientData.WeatherNow fetchWeatherSync() {
    long now = System.currentTimeMillis();
    AmbientData.WeatherNow cached = cachedWeather();
    long cachedAt = cached == null ? 0L : cached.fetchedAtMs;
    long lastAttempt = prefs.getLong(KEY_WX_ATTEMPT_AT, 0L);
    if (!weatherFetchDue(cachedAt, lastAttempt, now)) return cached;
    // Record the attempt before the request: a failure backs off a full interval too, so a
    // dead hub or a hub-side rate limit never turns into a retry loop from this screen.
    prefs.edit().putLong(KEY_WX_ATTEMPT_AT, now).apply();
    String base = hubHttpBase.get();
    if (base != null) base = base.replaceAll("/+$", "");
    if (base == null || base.isEmpty()) return cached;
    JSONObject root = getJson(base + "/kiosk/weather");
    JSONObject weather = root == null ? null : root.optJSONObject("weather");
    AmbientData.WeatherNow fresh =
        root != null && root.optBoolean("ok", false)
            ? parseKioskWeather(weather, now, false)
            : null;
    if (fresh == null) return cached;
    prefs
        .edit()
        .putString(KEY_WX_JSON, weather.toString())
        .putLong(KEY_WX_AT, now)
        .apply();
    return fresh;
  }

  /**
   * The 12-hour gate, kept pure so the host tests can pin it: no request while the cache is
   * younger than the interval, and no request within an interval of the previous attempt
   * (successful or not). {@code cachedAtMs} is 0 when there is no cache.
   */
  static boolean weatherFetchDue(long cachedAtMs, long lastAttemptMs, long nowMs) {
    if (cachedAtMs > 0L && nowMs - cachedAtMs < WEATHER_MIN_INTERVAL_MS) return false;
    return nowMs - lastAttemptMs >= WEATHER_MIN_INTERVAL_MS;
  }

  /**
   * Map the hub's weather payload (the inner {@code weather} object) onto the screen model.
   * Returns null when anything the screen must show is missing — the caller then falls back
   * to the cache rather than rendering half a reading. Temperatures may be JSON numbers or
   * numeric strings; both are accepted.
   */
  static AmbientData.WeatherNow parseKioskWeather(
      JSONObject weather, long fetchedAtMs, boolean fromCache) {
    if (weather == null) return null;
    JSONArray forecast = weather.optJSONArray("forecast");
    if (forecast == null || forecast.length() == 0) return null;
    JSONObject today = forecast.optJSONObject(0);
    if (today == null) return null;
    Integer low = optTemp(today, "temp_min");
    Integer high = optTemp(today, "temp_max");
    if (low == null || high == null) return null;
    String condition = today.optString("text").trim();
    JSONObject current = weather.optJSONObject("current");
    String summary = current == null ? "" : current.optString("summary").trim();
    String detail = current == null ? "" : current.optString("details").trim();
    if (condition.isEmpty()) condition = summaryHead(summary);
    if (condition.isEmpty()) return null;
    JSONObject location = weather.optJSONObject("location");
    String city = location == null ? "" : location.optString("name").trim();
    String source = weather.optString("source").trim();
    return new AmbientData.WeatherNow(
        city, condition, detail, low, high, source, fetchedAtMs, fromCache);
  }

  /** First clause of a summary text, capped — a condition line, never the whole paragraph. */
  private static String summaryHead(String summary) {
    if (summary == null) return "";
    String head = summary;
    int cut = head.length();
    for (char sep : new char[] {'，', '。', '；', ',', ';', '、'}) {
      int at = head.indexOf(sep);
      if (at >= 0 && at < cut) cut = at;
    }
    head = head.substring(0, cut).trim();
    if (head.length() > 12) head = head.substring(0, 12);
    return head;
  }

  /** temp_max/temp_min arrive as numbers or numeric strings depending on the hub's parser. */
  private static Integer optTemp(JSONObject obj, String key) {
    if (obj == null || obj.isNull(key)) return null;
    Object value = obj.opt(key);
    try {
      if (value instanceof Number) return (int) Math.round(((Number) value).doubleValue());
      if (value instanceof String) {
        String text = ((String) value).trim();
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("^[+-]?\\d+(\\.\\d+)?").matcher(text);
        if (m.find()) return (int) Math.round(Double.parseDouble(m.group()));
      }
    } catch (NumberFormatException e) {
      return null;
    }
    return null;
  }

  private AmbientData.WeatherNow cachedWeather() {
    long at = prefs.getLong(KEY_WX_AT, 0L);
    if (at == 0L) return null;
    String json = prefs.getString(KEY_WX_JSON, null);
    if (json == null) return null;
    try {
      return parseKioskWeather(new JSONObject(json), at, true);
    } catch (Exception e) {
      return null;
    }
  }

  // ---- news ----

  void fetchNews(final NewsCallback callback) {
    io.execute(
        () -> {
          final AmbientData.NewsFeed result = fetchNewsSync();
          main.post(() -> callback.done(result));
        });
  }

  private AmbientData.NewsFeed fetchNewsSync() {
    String base = hubHttpBase.get();
    if (base != null) base = base.replaceAll("/+$", "");
    if (base != null && !base.isEmpty()) {
      JSONObject data = getJson(base + "/news.json");
      JSONArray items = data == null ? null : data.optJSONArray("items");
      if (items != null && items.length() > 0) {
        List<AmbientData.NewsItem> parsed = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
          JSONObject item = items.optJSONObject(i);
          if (item == null) continue;
          String title = item.optString("title").trim();
          if (title.isEmpty()) continue;
          String category = item.optString("category").trim();
          parsed.add(
              new AmbientData.NewsItem(
                  category.isEmpty() ? "综合" : category,
                  title,
                  item.optString("summary").trim()));
        }
        if (!parsed.isEmpty()) {
          if (parsed.size() > MAX_NEWS_ITEMS)
            parsed = new ArrayList<>(parsed.subList(0, MAX_NEWS_ITEMS));
          return new AmbientData.NewsFeed(data.optString("date").trim(), parsed, true);
        }
      }
    }
    return new AmbientData.NewsFeed("", FALLBACK_NEWS, false);
  }

  /** The hub rate-limits /kiosk/weather: this client asks at most once per 12 hours. */
  static final long WEATHER_MIN_INTERVAL_MS = 12 * 60 * 60_000L;

  private static final int MAX_NEWS_ITEMS = 6;

  private static final String KEY_WX_JSON = "wx_json";
  private static final String KEY_WX_AT = "wx_at";
  private static final String KEY_WX_ATTEMPT_AT = "wx_attempt_at";

  /**
   * The web prototype's four category lines, kept as honest placeholders (see the TODO in the
   * class doc): they describe what each category covers, they are not news about any specific
   * day.
   */
  static final List<AmbientData.NewsItem> FALLBACK_NEWS =
      Arrays.asList(
          new AmbientData.NewsItem("模型", "模型动态", "大模型新版本、能力评测与价格调整方面的进展汇总。"),
          new AmbientData.NewsItem("产品", "产品动态", "AI 产品更新、重要功能上线与应用落地方面的消息汇总。"),
          new AmbientData.NewsItem("行业", "行业动态", "公司动态、投融资与合作方面的消息汇总。"),
          new AmbientData.NewsItem("政策", "政策动态", "监管政策与开源社区进展方面的消息汇总。"));
}

package com.justhalfbit.thinksmart;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
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
 * "details": {…}, "temp": …, "condition": …}, "forecast": [{"text": …, "temp_max": …,
 * "temp_min": …}, …], "source": …}}} — {@code current.temp} (the live temperature) and
 * {@code current.condition} (the live condition wording) are optional and used only when
 * present. Note {@code current.details} is an object of label→value pairs
 * (wind, humidity, …), not a string; {@link #formatDetails} flattens it for display.
 * the hub decides the city, the client never hardcodes one. The endpoint is rate-limited, so
 * successful readings are refreshed after {@link #WEATHER_MIN_INTERVAL_MS}: the last good
 * payload is kept verbatim in a prefs cache together with the client fetch time, fresh cache
 * is served without any request, and a failed fetch falls back to the cache however old (with
 * the next attempt deferred five minutes). Nothing is ever invented: with no cache the
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
    // Persist attempts so failures retry after five minutes even across screen/app restarts.
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
   * Successful cache lasts 3 hours. Without fresh cache, retry five minutes after the
   * previous attempt. {@code cachedAtMs} is 0 when there is no usable cache.
   */
  static boolean weatherFetchDue(long cachedAtMs, long lastAttemptMs, long nowMs) {
    if (cachedAtMs > 0L && nowMs - cachedAtMs < WEATHER_MIN_INTERVAL_MS) return false;
    return lastAttemptMs <= 0L || nowMs - lastAttemptMs >= WEATHER_RETRY_INTERVAL_MS;
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
    String detail = current == null ? "" : formatDetails(current.opt("details"));
    List<AmbientData.DetailRow> detailRows =
        current == null ? new ArrayList<>() : parseDetailRows(current.opt("details"));
    List<AmbientData.ForecastDay> days = parseForecast(forecast);
    // The hub's live condition (current.condition) describes what it is like right now and
    // beats today's forecast wording; without it the forecast text, then the summary head,
    // stand in as before. current.temp is optional in the same way — absent stays null and
    // the screen falls back to the plain headline.
    String currentCondition = current == null ? "" : current.optString("condition").trim();
    if (!currentCondition.isEmpty()) condition = currentCondition;
    Integer currentTemp = optTemp(current, "temp");
    if (condition.isEmpty()) condition = summaryHead(summary);
    if (condition.isEmpty()) return null;
    JSONObject location = weather.optJSONObject("location");
    String city = location == null ? "" : location.optString("name").trim();
    String source = weather.optString("source").trim();
    return new AmbientData.WeatherNow(
        city,
        condition,
        detail,
        detailRows,
        days,
        low,
        high,
        currentTemp,
        source,
        fetchedAtMs,
        fromCache);
  }

  /**
   * The detail column's rows from the hub's details pairs, in the screen's fixed order:
   * feels-like, humidity, wind. The wind pair's label is the direction word itself (the
   * hub keys it that way, e.g. "东风" → "2级"), so it becomes the row's name verbatim.
   * Missing entries are simply absent from the list; unparseable input yields no rows.
   */
  static List<AmbientData.DetailRow> parseDetailRows(Object details) {
    List<AmbientData.DetailRow> rows = new ArrayList<>();
    JSONObject obj = detailsObject(details);
    if (obj == null) return rows;
    String feels = detailValue(obj, "体感温度");
    if (!feels.isEmpty()) rows.add(new AmbientData.DetailRow("体感", feels));
    String humidity = detailValue(obj, "相对湿度");
    if (!humidity.isEmpty()) rows.add(new AmbientData.DetailRow("湿度", humidity));
    Iterator<String> keys = obj.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (key == null) continue;
      String label = key.trim();
      if (!label.endsWith("风")) continue;
      String value = detailValue(obj, key);
      if (!value.isEmpty()) rows.add(new AmbientData.DetailRow(label, value));
      break;
    }
    return rows;
  }

  /** Scalar value of one details entry, trimmed; "" for missing/nested/unusable values. */
  private static String detailValue(JSONObject obj, String key) {
    if (obj == null || obj.isNull(key)) return "";
    Object value = obj.opt(key);
    if (value == null || value == JSONObject.NULL) return "";
    if (value instanceof JSONObject || value instanceof JSONArray) return "";
    return String.valueOf(value).trim();
  }

  /** The details as an object: a JSONObject directly, or a string holding one. Else null. */
  private static JSONObject detailsObject(Object details) {
    if (details instanceof JSONObject) return (JSONObject) details;
    if (details instanceof String) {
      String text = ((String) details).trim();
      if (text.startsWith("{")) {
        try {
          return new JSONObject(text);
        } catch (Exception e) {
          return null;
        }
      }
    }
    return null;
  }

  /** All forecast days the hub sent, in order; temps null where a day did not report them. */
  static List<AmbientData.ForecastDay> parseForecast(JSONArray forecast) {
    List<AmbientData.ForecastDay> days = new ArrayList<>();
    if (forecast == null) return days;
    for (int i = 0; i < forecast.length(); i++) {
      JSONObject day = forecast.optJSONObject(i);
      if (day == null) continue;
      days.add(
          new AmbientData.ForecastDay(
              day.optString("text").trim(), optTemp(day, "temp_max"), optTemp(day, "temp_min")));
    }
    return days;
  }

  /** The forecast card's range line: "{high}° / {low}°", degrading to whichever side exists. */
  static String forecastRangeText(Integer high, Integer low) {
    if (high != null && low != null) return high + "° / " + low + "°";
    if (high != null) return high + "°";
    if (low != null) return low + "°";
    return "";
  }

  /**
   * Flatten the hub's current-conditions details into one short human-readable line.
   *
   * <p>The hub sends an object of label→value pairs (its page parser produces a dict, e.g.
   * {@code {"北风":"2级","相对湿度":"62%","紫外线":"弱","体感温度":"7℃"}}); pairs are joined as
   * 「label+value」 with " · ", at most {@link #MAX_DETAIL_ITEMS} of them, with the two wordy
   * labels shortened （相对湿度→湿度， 体感温度→体感）. A JSONObject's iteration order is not
   * portable (hash order in the reference parser, document order on Android), so known labels
   * are ranked for display — feels-like first, then wind, humidity, UV — and the rest keep
   * their relative order. A plain string is accepted for compatibility; a string that itself
   * holds a JSON object is parsed and formatted the same way. Raw JSON never reaches the
   * screen: anything unparseable becomes "".
   */
  static String formatDetails(Object details) {
    if (details == null || details == JSONObject.NULL) return "";
    if (details instanceof JSONObject) return formatDetailsObject((JSONObject) details);
    if (details instanceof String) {
      String text = ((String) details).trim();
      if (text.startsWith("{")) {
        try {
          return formatDetailsObject(new JSONObject(text));
        } catch (Exception e) {
          return "";
        }
      }
      return text;
    }
    return String.valueOf(details).trim();
  }

  private static String formatDetailsObject(JSONObject obj) {
    List<String[]> pairs = new ArrayList<>();
    Iterator<String> keys = obj.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (key == null) continue;
      String label = key.trim();
      Object value = obj.opt(key);
      if (label.isEmpty() || value == null || value == JSONObject.NULL) continue;
      // A nested object/array would print as raw JSON — skip it outright.
      if (value instanceof JSONObject || value instanceof JSONArray) continue;
      String text = String.valueOf(value).trim();
      if (text.isEmpty()) continue;
      pairs.add(new String[] {label, text});
    }
    // Stable sort: ranked labels come first, unranked ones keep their iteration order.
    pairs.sort((a, b) -> Integer.compare(detailLabelRank(a[0]), detailLabelRank(b[0])));
    StringBuilder out = new StringBuilder();
    int limit = Math.min(pairs.size(), MAX_DETAIL_ITEMS);
    for (int i = 0; i < limit; i++) {
      if (out.length() > 0) out.append(" · ");
      out.append(detailLabel(pairs.get(i)[0])).append(pairs.get(i)[1]);
    }
    return out.toString();
  }

  /** Display rank for known detail labels; everything else shares the last rank. */
  private static int detailLabelRank(String label) {
    if (label.equals("体感温度")) return 0;
    if (label.endsWith("风")) return 1;
    if (label.equals("相对湿度")) return 2;
    if (label.equals("紫外线")) return 3;
    return 4;
  }

  /** Shorten the two wordy labels; the rest are already compact. */
  private static String detailLabel(String label) {
    switch (label) {
      case "相对湿度":
        return "湿度";
      case "体感温度":
        return "体感";
      default:
        return label;
    }
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

  /** Temperatures (temp_max/temp_min, current.temp) arrive as numbers or numeric strings. */
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

  /** Successful weather readings remain fresh for 3 hours. */
  static final long WEATHER_MIN_INTERVAL_MS = 3 * 60 * 60_000L;
  static final long WEATHER_RETRY_INTERVAL_MS = 5 * 60_000L;

  private static final int MAX_NEWS_ITEMS = 6;

  /** The meta line shows at most this many current-conditions detail pairs. */
  private static final int MAX_DETAIL_ITEMS = 4;

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

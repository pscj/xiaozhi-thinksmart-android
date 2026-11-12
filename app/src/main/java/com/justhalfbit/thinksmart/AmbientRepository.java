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
 * <p>Weather: Open-Meteo for Beijing (39.9, 116.4) — no API key, two calls (forecast + air
 * quality). Nothing is ever invented: a failed fetch falls back to the last successful reading
 * (kept in a small prefs cache and marked {@code fromCache} so the UI can date it), and with no
 * cache the caller gets null and the UI shows a neutral "unavailable" line instead of numbers.
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
    JSONObject forecast =
        getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                + "&current=temperature_2m,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=Asia%2FShanghai&forecast_days=1");
    JSONObject daily = forecast == null ? null : forecast.optJSONObject("daily");
    JSONArray codes = daily == null ? null : daily.optJSONArray("weather_code");
    int code = codes == null || codes.length() == 0 ? -1 : codes.optInt(0, -1);
    if (daily == null || code < 0) return cachedWeather();
    JSONArray lows = daily.optJSONArray("temperature_2m_min");
    JSONArray highs = daily.optJSONArray("temperature_2m_max");
    if (lows == null || lows.length() == 0 || highs == null || highs.length() == 0)
      return cachedWeather();
    int low = (int) Math.round(lows.optDouble(0));
    int high = (int) Math.round(highs.optDouble(0));
    JSONObject current = forecast.optJSONObject("current");
    Integer currentTemp =
        current == null || current.isNull("temperature_2m")
            ? null
            : (int) Math.round(current.optDouble("temperature_2m"));
    JSONObject air =
        getJson(
            "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + LAT
                + "&longitude=" + LON + "&current=us_aqi");
    JSONObject airCurrent = air == null ? null : air.optJSONObject("current");
    Integer aqi =
        airCurrent == null || airCurrent.isNull("us_aqi")
            ? null
            : (int) Math.round(airCurrent.optDouble("us_aqi"));
    AmbientData.WeatherNow now =
        new AmbientData.WeatherNow(
            AmbientData.WeatherText.of(code),
            currentTemp,
            low,
            high,
            aqi,
            System.currentTimeMillis(),
            false);
    prefs
        .edit()
        .putString(KEY_WX_COND, now.condition)
        .putInt(KEY_WX_LOW, now.lowTemp)
        .putInt(KEY_WX_HIGH, now.highTemp)
        .putInt(KEY_WX_CUR, now.currentTemp == null ? Integer.MIN_VALUE : now.currentTemp)
        .putInt(KEY_WX_AQI, now.aqi == null ? -1 : now.aqi)
        .putLong(KEY_WX_AT, now.fetchedAtMs)
        .apply();
    return now;
  }

  private AmbientData.WeatherNow cachedWeather() {
    long at = prefs.getLong(KEY_WX_AT, 0L);
    if (at == 0L) return null;
    String condition = prefs.getString(KEY_WX_COND, null);
    if (condition == null) return null;
    int cur = prefs.getInt(KEY_WX_CUR, Integer.MIN_VALUE);
    int aqi = prefs.getInt(KEY_WX_AQI, -1);
    return new AmbientData.WeatherNow(
        condition,
        cur == Integer.MIN_VALUE ? null : cur,
        prefs.getInt(KEY_WX_LOW, 0),
        prefs.getInt(KEY_WX_HIGH, 0),
        aqi < 0 ? null : aqi,
        at,
        true);
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

  private static final String LAT = "39.9";
  private static final String LON = "116.4";
  private static final int MAX_NEWS_ITEMS = 6;

  private static final String KEY_WX_COND = "wx_condition";
  private static final String KEY_WX_LOW = "wx_low";
  private static final String KEY_WX_HIGH = "wx_high";
  private static final String KEY_WX_CUR = "wx_current";
  private static final String KEY_WX_AQI = "wx_aqi";
  private static final String KEY_WX_AT = "wx_at";

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

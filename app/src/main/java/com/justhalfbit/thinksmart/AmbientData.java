package com.justhalfbit.thinksmart;

import java.util.List;

/**
 * Data for the ambient (standby) screen: today's weather (from the hub's /kiosk/weather) and
 * the day's AI-news list. Kept free of Android and JSON types so the pure parts run in the
 * host unit tests; JSON parsing lives in AmbientRepository.
 */
final class AmbientData {

  /** Icon families the ambient screen can draw for a condition wording. */
  enum WeatherIcon {
    SUN,
    SUN_CLOUD,
    CLOUD,
    RAIN,
    SNOW,
    FOG
  }

  /**
   * Map a condition wording （晴 / 多云 / 雷阵雨 …) onto the icon to draw for it. Order matters:
   * snow and rain win over the cloud/sun words they combine with, and 多云 (a sun behind cloud)
   * wins over a bare 晴 it may be paired with. Anything unrecognised draws as plain cloud.
   */
  static WeatherIcon classifyIcon(String condition) {
    if (condition == null) return WeatherIcon.CLOUD;
    String text = condition.trim();
    if (text.contains("雪")) return WeatherIcon.SNOW;
    if (text.contains("雨")) return WeatherIcon.RAIN;
    if (text.contains("雾") || text.contains("霾")) return WeatherIcon.FOG;
    if (text.contains("多云")) return WeatherIcon.SUN_CLOUD;
    if (text.contains("晴")) return WeatherIcon.SUN;
    return WeatherIcon.CLOUD;
  }

  /** One label→value row of the current-conditions detail column, e.g. 体感 → 6°. */
  static final class DetailRow {
    final String name;
    final String value;

    DetailRow(String name, String value) {
      this.name = name;
      this.value = value;
    }
  }

  /** One forecast day for the strip under the weather cluster. Temps null when unreported. */
  static final class ForecastDay {
    final String text;
    final Integer high;
    final Integer low;

    ForecastDay(String text, Integer high, Integer low) {
      this.text = text;
      this.high = high;
      this.low = low;
    }
  }

  static final class WeatherNow {
    /** Location name as the hub reported it (location.name), "" when absent. Never invented. */
    final String city;
    /**
     * Condition wording, e.g. 晴 / 多云 / 小雨 — the hub's current condition when it sends
     * one, else today's forecast text, else the summary's head.
     */
    final String condition;
    /** Current-conditions detail text from the hub, "" when absent. Display truncates it. */
    final String detail;
    /**
     * The detail column's rows （体感 / 湿度 / wind), already in display order, missing entries
     * simply absent. Empty when the hub sent no usable detail pairs.
     */
    final List<DetailRow> detailRows;
    /** Forecast days in hub order (today first); the strip shows the first three. */
    final List<ForecastDay> forecast;
    final int lowTemp;
    final int highTemp;
    /** Current temperature (current.temp), null when the hub did not send one. Never invented. */
    final Integer currentTemp;
    /** Weather provider name as reported (source, e.g. 和风天气), "" when absent. */
    final String source;
    /** When this reading was fetched from the hub, epoch ms (client clock). */
    final long fetchedAtMs;
    /** True when this reading came from the on-disk cache rather than a fresh fetch. */
    final boolean fromCache;

    WeatherNow(
        String city,
        String condition,
        String detail,
        List<DetailRow> detailRows,
        List<ForecastDay> forecast,
        int lowTemp,
        int highTemp,
        Integer currentTemp,
        String source,
        long fetchedAtMs,
        boolean fromCache) {
      this.city = city;
      this.condition = condition;
      this.detail = detail;
      this.detailRows = detailRows;
      this.forecast = forecast;
      this.lowTemp = lowTemp;
      this.highTemp = highTemp;
      this.currentTemp = currentTemp;
      this.source = source;
      this.fetchedAtMs = fetchedAtMs;
      this.fromCache = fromCache;
    }
  }

  static final class NewsItem {
    final String category;
    final String title;
    final String summary;

    NewsItem(String category, String title, String summary) {
      this.category = category;
      this.title = title;
      this.summary = summary;
    }
  }

  static final class NewsFeed {
    /** The feed's own date string (yyyy-MM-dd) when the hub provided one, else "". */
    final String date;
    final List<NewsItem> items;
    /** False = the bundled category placeholders, shown until the hub feed is wired up. */
    final boolean fromHub;

    NewsFeed(String date, List<NewsItem> items, boolean fromHub) {
      this.date = date;
      this.items = items;
      this.fromHub = fromHub;
    }
  }

  private AmbientData() {}
}

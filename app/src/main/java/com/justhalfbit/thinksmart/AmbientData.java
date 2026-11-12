package com.justhalfbit.thinksmart;

import java.util.List;

/**
 * Data for the ambient (standby) screen: one day of Beijing weather and the day's AI-news list.
 * Kept free of Android and JSON types so the pure parts (WMO weather-code wording) run in the
 * host unit tests.
 */
final class AmbientData {

  static final class WeatherNow {
    /** Wording for the day, e.g. 晴 / 多云 / 小雨. */
    final String condition;
    /** Current temperature in °C, null when the source did not give one. */
    final Integer currentTemp;
    final int lowTemp;
    final int highTemp;
    /** US AQI, null when the air-quality call failed. */
    final Integer aqi;
    /** When this reading was fetched, epoch ms. */
    final long fetchedAtMs;
    /** True when this is the last good reading shown again after a failed refresh. */
    final boolean fromCache;

    WeatherNow(
        String condition,
        Integer currentTemp,
        int lowTemp,
        int highTemp,
        Integer aqi,
        long fetchedAtMs,
        boolean fromCache) {
      this.condition = condition;
      this.currentTemp = currentTemp;
      this.lowTemp = lowTemp;
      this.highTemp = highTemp;
      this.aqi = aqi;
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

  /** WMO weather codes (Open-Meteo {@code weather_code}) -> short Chinese wording. */
  static final class WeatherText {
    static String of(int code) {
      switch (code) {
        case 0:
          return "晴";
        case 1:
          return "晴间多云";
        case 2:
          return "多云";
        case 3:
          return "阴";
        case 45:
        case 48:
          return "雾";
        case 51:
        case 53:
        case 55:
          return "毛毛雨";
        case 56:
        case 57:
          return "冻毛毛雨";
        case 61:
          return "小雨";
        case 63:
          return "中雨";
        case 65:
          return "大雨";
        case 66:
        case 67:
          return "冻雨";
        case 71:
          return "小雪";
        case 73:
          return "中雪";
        case 75:
          return "大雪";
        case 77:
          return "雪粒";
        case 80:
        case 81:
          return "阵雨";
        case 82:
          return "暴雨";
        case 85:
        case 86:
          return "阵雪";
        case 95:
          return "雷阵雨";
        case 96:
        case 99:
          return "冰雹";
        default:
          return "未知";
      }
    }

    /** AQI band wording for the US scale Open-Meteo reports. */
    static String aqiBand(int aqi) {
      if (aqi <= 50) return "优";
      if (aqi <= 100) return "良";
      if (aqi <= 150) return "轻度污染";
      if (aqi <= 200) return "中度污染";
      if (aqi <= 300) return "重度污染";
      return "严重污染";
    }

    private WeatherText() {}
  }

  private AmbientData() {}
}

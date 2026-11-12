package com.justhalfbit.thinksmart;

import java.util.List;

/**
 * Data for the ambient (standby) screen: today's weather (from the hub's /kiosk/weather) and
 * the day's AI-news list. Kept free of Android and JSON types so the pure parts run in the
 * host unit tests; JSON parsing lives in AmbientRepository.
 */
final class AmbientData {

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
        int lowTemp,
        int highTemp,
        Integer currentTemp,
        String source,
        long fetchedAtMs,
        boolean fromCache) {
      this.city = city;
      this.condition = condition;
      this.detail = detail;
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

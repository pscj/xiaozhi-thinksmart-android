package com.justhalfbit.thinksmart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Host-side tests for the hub /kiosk/weather mapping and the 12-hour fetch gate. The payload
 * shape pinned here is the one xiaozhi-hub-server's handle_kiosk_weather returns:
 * {"location": {"name": …}, "current": {"summary": …, "details": …}, "forecast":
 * [{"text": …, "temp_max": …, "temp_min": …}], "source": …}.
 */
public class AmbientWeatherTest {

  private static final long HOUR = 60 * 60_000L;

  private static JSONObject weather(String body) throws Exception {
    return new JSONObject(body);
  }

  @Test
  public void parsesTypicalPayload() throws Exception {
    AmbientData.WeatherNow now =
        AmbientRepository.parseKioskWeather(
            weather(
                "{\"location\": {\"id\": \"101010100\", \"name\": \"北京\"},"
                    + "\"current\": {\"summary\": \"晴，微风\", \"details\": \"体感温度 7℃\"},"
                    + "\"forecast\": [{\"date\": \"2026-11-12\", \"text\": \"晴\","
                    + " \"temp_max\": 11, \"temp_min\": 2}],"
                    + "\"source\": \"和风天气\", \"cached\": false}"),
            123_000L,
            false);
    assertNotNull(now);
    assertEquals("北京", now.city);
    assertEquals("晴", now.condition);
    assertEquals("体感温度 7℃", now.detail);
    assertEquals(2, now.lowTemp);
    assertEquals(11, now.highTemp);
    assertEquals("和风天气", now.source);
    assertEquals(123_000L, now.fetchedAtMs);
    assertFalse(now.fromCache);
  }

  @Test
  public void parsesStringTemperatures() throws Exception {
    AmbientData.WeatherNow now =
        AmbientRepository.parseKioskWeather(
            weather(
                "{\"forecast\": [{\"text\": \"多云\", \"temp_max\": \"16\", \"temp_min\": \"3\"}]}"),
            0L,
            true);
    assertNotNull(now);
    assertEquals(3, now.lowTemp);
    assertEquals(16, now.highTemp);
    assertEquals("", now.city);
    assertEquals("", now.source);
    assertTrue(now.fromCache);
  }

  @Test
  public void conditionFallsBackToSummaryHead() throws Exception {
    AmbientData.WeatherNow now =
        AmbientRepository.parseKioskWeather(
            weather(
                "{\"current\": {\"summary\": \"晴间多云，午后有风\", \"details\": \"\"},"
                    + "\"forecast\": [{\"text\": \"\", \"temp_max\": 9, \"temp_min\": 1}]}"),
            0L,
            false);
    assertNotNull(now);
    assertEquals("晴间多云", now.condition);
  }

  @Test
  public void rejectsUnusablePayloads() throws Exception {
    assertNull(AmbientRepository.parseKioskWeather(null, 0L, false));
    assertNull(
        AmbientRepository.parseKioskWeather(weather("{\"forecast\": []}"), 0L, false));
    // Missing temperatures: the screen must not invent a range.
    assertNull(
        AmbientRepository.parseKioskWeather(
            weather("{\"forecast\": [{\"text\": \"晴\"}]}"), 0L, false));
    // No condition wording anywhere: nothing honest to show.
    assertNull(
        AmbientRepository.parseKioskWeather(
            weather("{\"forecast\": [{\"temp_max\": 9, \"temp_min\": 1}]}"), 0L, false));
  }

  @Test
  public void fetchGateHonoursTheInterval() {
    long now = 100 * HOUR;
    // Fresh cache: never fetch, however old the last attempt is.
    assertFalse(AmbientRepository.weatherFetchDue(now - HOUR, 0L, now));
    assertFalse(AmbientRepository.weatherFetchDue(now - 11 * HOUR, now - 11 * HOUR, now));
    // Stale cache and the last attempt a full interval ago: fetch.
    assertTrue(AmbientRepository.weatherFetchDue(now - 12 * HOUR, now - 12 * HOUR, now));
    assertTrue(AmbientRepository.weatherFetchDue(now - 30 * HOUR, now - 13 * HOUR, now));
    // No cache at all and never attempted: fetch.
    assertTrue(AmbientRepository.weatherFetchDue(0L, 0L, now));
    // No cache but a recent (failed) attempt: back off the full interval.
    assertFalse(AmbientRepository.weatherFetchDue(0L, now - HOUR, now));
    // Stale cache but a recent failed attempt: serve the cache, do not retry yet.
    assertFalse(AmbientRepository.weatherFetchDue(now - 30 * HOUR, now - 2 * HOUR, now));
  }
}

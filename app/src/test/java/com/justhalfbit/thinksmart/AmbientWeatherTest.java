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
 * {"location": {"name": …}, "current": {"summary": …, "details": {…}}, "forecast":
 * [{"text": …, "temp_max": …, "temp_min": …}], "source": …}. Note details is an object
 * of label→value pairs (the hub's page parser yields a dict), not a string.
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
                    + "\"current\": {\"summary\": \"晴，微风\", \"details\":"
                    + " {\"北风\": \"2级\", \"相对湿度\": \"62%\", \"紫外线\": \"弱\","
                    + " \"体感温度\": \"7℃\"}},"
                    + "\"forecast\": [{\"date\": \"2026-11-12\", \"text\": \"晴\","
                    + " \"temp_max\": 11, \"temp_min\": 2}],"
                    + "\"source\": \"和风天气\", \"cached\": false}"),
            123_000L,
            false);
    assertNotNull(now);
    assertEquals("北京", now.city);
    assertEquals("晴", now.condition);
    // Object details are flattened to a readable line: labels ranked (feels-like, wind,
    // humidity, UV), wordy labels shortened, no JSON punctuation ever reaches the screen.
    assertEquals("体感7℃ · 北风2级 · 湿度62% · 紫外线弱", now.detail);
    assertFalse(now.detail.contains("{"));
    assertFalse(now.detail.contains("\""));
    assertFalse(now.detail.contains(":"));
    assertEquals(2, now.lowTemp);
    assertEquals(11, now.highTemp);
    // No current.temp in this payload: stays null, the headline keeps its plain form.
    assertNull(now.currentTemp);
    assertEquals("和风天气", now.source);
    assertEquals(123_000L, now.fetchedAtMs);
    assertFalse(now.fromCache);
  }

  @Test
  public void detailsStringForms() throws Exception {
    String forecast = "\"forecast\": [{\"text\": \"晴\", \"temp_max\": 11, \"temp_min\": 2}]";
    // A details string that itself holds a JSON object is parsed and flattened the
    // same way as an object value — never shown raw.
    AmbientData.WeatherNow jsonString =
        AmbientRepository.parseKioskWeather(
            weather(
                "{\"current\": {\"details\": \"{\\\"北风\\\":\\\"2级\\\",\\\"相对湿度\\\":\\\"62%\\\"}\"},"
                    + forecast
                    + "}"),
            0L,
            false);
    assertNotNull(jsonString);
    assertEquals("北风2级 · 湿度62%", jsonString.detail);
    // A plain-text details string is used verbatim.
    AmbientData.WeatherNow plain =
        AmbientRepository.parseKioskWeather(
            weather("{\"current\": {\"details\": \"体感温度 7℃\"}," + forecast + "}"), 0L, false);
    assertNotNull(plain);
    assertEquals("体感温度 7℃", plain.detail);
    // A "{" string that does not parse becomes no detail at all, not raw JSON.
    AmbientData.WeatherNow broken =
        AmbientRepository.parseKioskWeather(
            weather("{\"current\": {\"details\": \"{broken\"}," + forecast + "}"), 0L, false);
    assertNotNull(broken);
    assertEquals("", broken.detail);
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
  public void parsesCurrentTempAndCondition() throws Exception {
    String forecast = "\"forecast\": [{\"text\": \"晴\", \"temp_max\": 15, \"temp_min\": 1}]";
    // String form, as the hub's parser may emit: the live condition wins over the
    // forecast wording, and the temperature is read as a plain number.
    AmbientData.WeatherNow stringForm =
        AmbientRepository.parseKioskWeather(
            weather("{\"current\": {\"temp\": \"10\", \"condition\": \"霾\"}," + forecast + "}"),
            0L,
            false);
    assertNotNull(stringForm);
    assertEquals(Integer.valueOf(10), stringForm.currentTemp);
    assertEquals("霾", stringForm.condition);
    // Numeric form with a degree suffix and a negative value parses the same way.
    AmbientData.WeatherNow numericForm =
        AmbientRepository.parseKioskWeather(
            weather("{\"current\": {\"temp\": \"-3°\"}," + forecast + "}"), 0L, false);
    assertNotNull(numericForm);
    assertEquals(Integer.valueOf(-3), numericForm.currentTemp);
    // No live condition: today's forecast text still stands, as before.
    assertEquals("晴", numericForm.condition);
    // An unparseable temp is simply absent, never a reason to reject the reading.
    AmbientData.WeatherNow badTemp =
        AmbientRepository.parseKioskWeather(
            weather("{\"current\": {\"temp\": \"暂无\"}," + forecast + "}"), 0L, false);
    assertNotNull(badTemp);
    assertNull(badTemp.currentTemp);
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
    // Failed requests no longer block retries for twelve hours.
    assertTrue(AmbientRepository.weatherFetchDue(0L, now - HOUR, now));
    assertTrue(AmbientRepository.weatherFetchDue(now - 30 * HOUR, now - 2 * HOUR, now));
  }

  @Test
  public void failedRequestsRetryAtFiveMinuteBoundaryWithOrWithoutOldCache() {
    long now = 100 * HOUR;
    long retry = 5 * 60_000L;
    for (long cache : new long[] {0L, now - 30 * HOUR}) {
      assertFalse(AmbientRepository.weatherFetchDue(cache, now, now));
      assertFalse(AmbientRepository.weatherFetchDue(cache, now - retry + 1, now));
      assertTrue(AmbientRepository.weatherFetchDue(cache, now - retry, now));
      assertTrue(AmbientRepository.weatherFetchDue(cache, now - retry - 1, now));
    }
  }

  @Test
  public void successfulRetryStartsTwelveHourWindowAgain() {
    long succeeded = 100 * HOUR;
    long expiry = succeeded + 12 * HOUR;
    assertFalse(AmbientRepository.weatherFetchDue(succeeded, succeeded, succeeded + 5 * 60_000L));
    assertFalse(AmbientRepository.weatherFetchDue(succeeded, succeeded, expiry - 1));
    assertTrue(AmbientRepository.weatherFetchDue(succeeded, succeeded, expiry));
    // A failed refresh then waits five minutes; it does not make stale cache fresh.
    assertFalse(AmbientRepository.weatherFetchDue(succeeded, expiry, expiry + 5 * 60_000L - 1));
    assertTrue(AmbientRepository.weatherFetchDue(succeeded, expiry, expiry + 5 * 60_000L));
  }
}

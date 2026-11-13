package com.justhalfbit.thinksmart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
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
    assertFalse(AmbientRepository.weatherFetchDue(now - 3 * HOUR + 1, now - 3 * HOUR + 1, now));
    // Stale cache and the last attempt a full interval ago: fetch.
    assertTrue(AmbientRepository.weatherFetchDue(now - 3 * HOUR, now - 3 * HOUR, now));
    assertTrue(AmbientRepository.weatherFetchDue(now - 30 * HOUR, now - 13 * HOUR, now));
    // No cache at all and never attempted: fetch.
    assertTrue(AmbientRepository.weatherFetchDue(0L, 0L, now));
    // Failed requests no longer block retries for the whole freshness window.
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
  public void detailRowsFromObjectForm() throws Exception {
    AmbientData.WeatherNow now =
        AmbientRepository.parseKioskWeather(
            weather(
                "{\"current\": {\"summary\": \"晴，微风\", \"details\":"
                    + " {\"北风\": \"2级\", \"相对湿度\": \"62%\", \"紫外线\": \"弱\","
                    + " \"体感温度\": \"7℃\"}},"
                    + "\"forecast\": [{\"text\": \"晴\", \"temp_max\": 11, \"temp_min\": 2}]}"),
            0L,
            false);
    assertNotNull(now);
    // Fixed display order — feels-like, humidity, wind — whatever the hub's key order was.
    // 紫外线 is not a row: the cluster shows three rows at most.
    assertEquals(3, now.detailRows.size());
    assertEquals("体感", now.detailRows.get(0).name);
    assertEquals("7℃", now.detailRows.get(0).value);
    assertEquals("湿度", now.detailRows.get(1).name);
    assertEquals("62%", now.detailRows.get(1).value);
    // The wind pair's key IS the direction word; it becomes the row name verbatim.
    assertEquals("北风", now.detailRows.get(2).name);
    assertEquals("2级", now.detailRows.get(2).value);
  }

  @Test
  public void detailRowsFromStringFormAndMissingEntries() throws Exception {
    // A details string holding a JSON object parses into rows the same way.
    List<AmbientData.DetailRow> fromString =
        AmbientRepository.parseDetailRows("{\"相对湿度\":\"40%\",\"南风\":\"3级\"}");
    assertEquals(2, fromString.size());
    assertEquals("湿度", fromString.get(0).name);
    assertEquals("40%", fromString.get(0).value);
    assertEquals("南风", fromString.get(1).name);
    assertEquals("3级", fromString.get(1).value);
    // Missing entries are simply absent — no placeholder rows.
    List<AmbientData.DetailRow> onlyFeels =
        AmbientRepository.parseDetailRows(new JSONObject("{\"体感温度\":\"6°\"}"));
    assertEquals(1, onlyFeels.size());
    assertEquals("体感", onlyFeels.get(0).name);
    assertEquals("6°", onlyFeels.get(0).value);
    // Plain text, broken JSON and null yield no rows at all.
    assertTrue(AmbientRepository.parseDetailRows("体感温度 7℃").isEmpty());
    assertTrue(AmbientRepository.parseDetailRows("{broken").isEmpty());
    assertTrue(AmbientRepository.parseDetailRows(null).isEmpty());
  }

  @Test
  public void forecastParsingKeepsAllDaysAndCleansTemps() throws Exception {
    AmbientData.WeatherNow now =
        AmbientRepository.parseKioskWeather(
            weather(
                "{\"forecast\": ["
                    + "{\"text\": \"晴\", \"temp_max\": 12, \"temp_min\": -2},"
                    + "{\"text\": \"晴\", \"temp_max\": \"11°\", \"temp_min\": \"-3°\"},"
                    + "{\"text\": \"多云\", \"temp_max\": \"8\", \"temp_min\": \"-3\"},"
                    + "{\"text\": \"小雪\", \"temp_max\": \"暂无\", \"temp_min\": \"-9\"}]}"),
            0L,
            false);
    assertNotNull(now);
    // All days are kept in hub order — the strip picks the first three itself.
    assertEquals(4, now.forecast.size());
    assertEquals(Integer.valueOf(12), now.forecast.get(0).high);
    assertEquals(Integer.valueOf(-2), now.forecast.get(0).low);
    // String temperatures carrying a degree suffix clean up to plain numbers.
    assertEquals(Integer.valueOf(11), now.forecast.get(1).high);
    assertEquals(Integer.valueOf(-3), now.forecast.get(1).low);
    assertEquals("多云", now.forecast.get(2).text);
    // An unreported temperature stays null on that day instead of failing the parse.
    assertNull(now.forecast.get(3).high);
    assertEquals(Integer.valueOf(-9), now.forecast.get(3).low);
  }

  @Test
  public void classifyIconMapping() {
    assertEquals(AmbientData.WeatherIcon.SUN, AmbientData.classifyIcon("晴"));
    assertEquals(AmbientData.WeatherIcon.SUN_CLOUD, AmbientData.classifyIcon("多云"));
    assertEquals(AmbientData.WeatherIcon.CLOUD, AmbientData.classifyIcon("阴"));
    assertEquals(AmbientData.WeatherIcon.CLOUD, AmbientData.classifyIcon(""));
    assertEquals(AmbientData.WeatherIcon.CLOUD, AmbientData.classifyIcon(null));
    assertEquals(AmbientData.WeatherIcon.RAIN, AmbientData.classifyIcon("小雨"));
    assertEquals(AmbientData.WeatherIcon.RAIN, AmbientData.classifyIcon("雷阵雨"));
    assertEquals(AmbientData.WeatherIcon.SNOW, AmbientData.classifyIcon("小雪"));
    // Snow wins over the rain word it combines with, rain over a bare 晴.
    assertEquals(AmbientData.WeatherIcon.SNOW, AmbientData.classifyIcon("雨夹雪"));
    assertEquals(AmbientData.WeatherIcon.FOG, AmbientData.classifyIcon("雾"));
    assertEquals(AmbientData.WeatherIcon.FOG, AmbientData.classifyIcon("霾"));
  }

  @Test
  public void forecastRangeTextDegrades() {
    assertEquals("12° / -2°", AmbientRepository.forecastRangeText(12, -2));
    assertEquals("12°", AmbientRepository.forecastRangeText(12, null));
    assertEquals("-2°", AmbientRepository.forecastRangeText(null, -2));
    assertEquals("", AmbientRepository.forecastRangeText(null, null));
  }

  @Test
  public void successfulRetryStartsThreeHourWindowAgain() {
    long succeeded = 100 * HOUR;
    long expiry = succeeded + 3 * HOUR;
    assertFalse(AmbientRepository.weatherFetchDue(succeeded, succeeded, succeeded + 5 * 60_000L));
    assertFalse(AmbientRepository.weatherFetchDue(succeeded, succeeded, expiry - 1));
    assertTrue(AmbientRepository.weatherFetchDue(succeeded, succeeded, expiry));
    // A failed refresh then waits five minutes; it does not make stale cache fresh.
    assertFalse(AmbientRepository.weatherFetchDue(succeeded, expiry, expiry + 5 * 60_000L - 1));
    assertTrue(AmbientRepository.weatherFetchDue(succeeded, expiry, expiry + 5 * 60_000L));
  }
}

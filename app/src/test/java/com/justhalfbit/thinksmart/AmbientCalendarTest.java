package com.justhalfbit.thinksmart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import org.junit.Test;

/**
 * Host-side tests for the ambient screen's pure pieces. The lunar table was generated from
 * ICU4J's ChineseCalendar and cross-checked against it for every day of 1990-2040; these
 * assertions pin the independently known dates (Chinese New Year, famous leap months) so a
 * table edit that breaks the calendar fails loudly here.
 */
public class AmbientCalendarTest {

  @Test
  public void chineseNewYearDates() {
    assertEquals("农历正月初一", LunarCalendar.format(LocalDate.of(2023, 1, 22)));
    assertEquals("农历正月初一", LunarCalendar.format(LocalDate.of(2024, 2, 10)));
    assertEquals("农历正月初一", LunarCalendar.format(LocalDate.of(2025, 1, 29)));
    assertEquals("农历正月初一", LunarCalendar.format(LocalDate.of(2026, 2, 17)));
  }

  @Test
  public void knownLeapMonths() {
    // 2023 had a leap 2nd month starting 2023-03-22.
    LunarCalendar.LunarDate leap = LunarCalendar.fromSolar(LocalDate.of(2023, 3, 22));
    assertTrue(leap.leapMonth);
    assertEquals(2, leap.month);
    assertEquals(1, leap.day);
    assertEquals("农历闰二月初一", LunarCalendar.format(LocalDate.of(2023, 3, 22)));
    assertEquals(6, LunarCalendar.leapMonth(2017)); // 闰六月
    assertEquals(9, LunarCalendar.leapMonth(2014)); // 闰九月
    assertEquals(0, LunarCalendar.leapMonth(2024));
  }

  @Test
  public void ordinaryDates() {
    assertEquals("农历冬月廿五", LunarCalendar.format(LocalDate.of(2000, 1, 1)));
    // The day the ambient web prototype was laid out around.
    assertEquals("农历十月初三", LunarCalendar.format(LocalDate.of(2026, 11, 11)));
    assertEquals("农历十月初四", LunarCalendar.format(LocalDate.of(2026, 11, 12)));
    LunarCalendar.LunarDate plain = LunarCalendar.fromSolar(LocalDate.of(2024, 6, 15));
    assertFalse(plain.leapMonth);
  }

  @Test
  public void outOfRangeIsBlank() {
    assertEquals("", LunarCalendar.format(LocalDate.of(1800, 1, 1)));
    assertEquals("", LunarCalendar.format(LocalDate.of(2200, 1, 1)));
  }

  // The old weatherWording test pinned AmbientData.WeatherText (WMO code -> wording, AQI
  // bands). That mapping belonged to the removed Open-Meteo source; the hub's /kiosk/weather
  // sends ready-made wording, so the class and this test went away together. The kiosk
  // parsing and the 12 h gate are pinned by AmbientWeatherTest instead.
}

package com.justhalfbit.thinksmart;

import java.time.LocalDate;

/**
 * Solar -> Chinese lunar date, for the ambient (standby) screen.
 *
 * <p>Self-contained on purpose: {@code android.icu.util.ChineseCalendar} would do, but a pure
 * Java implementation runs in the host unit tests and never depends on ICU data being present in
 * a de-Googled AOSP build.
 *
 * <p>The {@code LUNAR_INFO} table (one word per lunar year, 1900-2100) was generated from
 * ICU4J's astronomical ChineseCalendar and cross-checked against known leap months (1990
 * 闰五月, 2014 闰九月, 2017 闰六月, 2023 闰二月, 2025 闰六月) and known Chinese New Year
 * dates. Encoding, as in the classic implementations: low nibble = leap month (0 = none), bit
 * 0x10000 = the leap month has 30 days, bit (0x10000 >> m) = regular month m has 30 days.
 */
public final class LunarCalendar {

  public static final class LunarDate {
    public final int year;
    public final int month; // 1..12
    public final int day; // 1..30
    public final boolean leapMonth;

    LunarDate(int year, int month, int day, boolean leapMonth) {
      this.year = year;
      this.month = month;
      this.day = day;
      this.leapMonth = leapMonth;
    }
  }

  private static final int BASE_YEAR = 1900;

  /** 1900-01-31 (solar) is lunar 1900-01-01. */
  private static final LocalDate BASE = LocalDate.of(1900, 1, 31);

  private static final int[] LUNAR_INFO = {
    0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
    0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x18da3, 0x095b0, 0x14977,
    0x04970, 0x0a4b0, 0x1b0b6, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
    0x06566, 0x0d4a0, 0x0ea50, 0x16a95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
    0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
    0x06ca0, 0x0b550, 0x15355, 0x04db0, 0x025b0, 0x18573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
    0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
    0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
    0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x1ad47, 0x0ab60, 0x09570,
    0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096e5, 0x092e0,
    0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
    0x0a950, 0x0b4a0, 0x0bca4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x05270, 0x0a930,
    0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4f0, 0x05260, 0x0ea65, 0x0d520,
    0x0daa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
    0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
    0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
    0x092e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
    0x092d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
    0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
    0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
    0x0d520,
  };

  private static int info(int year) {
    return LUNAR_INFO[year - BASE_YEAR];
  }

  /** The leap month of a lunar year, 0 when there is none. */
  public static int leapMonth(int year) {
    return info(year) & 0xf;
  }

  private static int leapDays(int year) {
    if (leapMonth(year) == 0) return 0;
    return (info(year) & 0x10000) != 0 ? 30 : 29;
  }

  private static int monthDays(int year, int month) {
    return (info(year) & (0x10000 >> month)) != 0 ? 30 : 29;
  }

  private static int yearDays(int year) {
    int sum = 348; // 12 * 29
    int bit = 0x8000;
    while (bit > 0x8) {
      if ((info(year) & bit) != 0) sum += 1;
      bit >>= 1;
    }
    return sum + leapDays(year);
  }

  /** Convert a solar date; null outside the table's 1900-2100 range. */
  public static LunarDate fromSolar(LocalDate date) {
    if (date.isBefore(BASE)) return null;
    int offset = (int) (date.toEpochDay() - BASE.toEpochDay());
    int year = BASE_YEAR;
    while (year <= 2100) {
      int days = yearDays(year);
      if (offset < days) break;
      offset -= days;
      year++;
    }
    if (year > 2100) return null;
    int leap = leapMonth(year);
    int month = 1;
    boolean isLeap = false;
    while (month <= 12) {
      int days = isLeap ? leapDays(year) : monthDays(year, month);
      if (offset < days) break;
      offset -= days;
      if (isLeap) {
        isLeap = false;
        month++;
      } else if (leap == month) {
        isLeap = true;
      } else {
        month++;
      }
    }
    return new LunarDate(year, month, offset + 1, isLeap);
  }

  private static final String[] MONTH_NAMES = {
    "正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月",
  };

  private static final String[] DAY_DIGITS = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

  public static String dayName(int day) {
    if (day >= 1 && day <= 10) return "初" + DAY_DIGITS[day - 1];
    if (day >= 11 && day <= 19) return "十" + DAY_DIGITS[day - 11];
    if (day == 20) return "二十";
    if (day >= 21 && day <= 29) return "廿" + DAY_DIGITS[day - 21];
    if (day == 30) return "三十";
    return "";
  }

  /** 「农历十月初三」/「农历闰二月初一」for the ambient clock; "" when out of range. */
  public static String format(LocalDate date) {
    LunarDate lunar = fromSolar(date);
    if (lunar == null) return "";
    String leap = lunar.leapMonth ? "闰" : "";
    return "农历" + leap + MONTH_NAMES[lunar.month - 1] + dayName(lunar.day);
  }

  private LunarCalendar() {}
}

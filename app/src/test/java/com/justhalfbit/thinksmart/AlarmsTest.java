package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;
import java.time.LocalDateTime;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AlarmsTest {
  // 2026-11-19 is a Thursday; 2026-11-20 Friday, 2026-11-23 Monday.
  private static LocalDateTime at(int year, int month, int day, int hour, int minute) {
    return LocalDateTime.of(year, month, day, hour, minute);
  }

  private static JSONArray days(int... values) {
    JSONArray array = new JSONArray();
    for (int v : values) array.put(v);
    return array;
  }

  private static JSONObject create(Alarms alarms, String time, String repeat, LocalDateTime now) {
    return alarms.create(time, repeat, null, 0, "", now);
  }

  @Test public void onceLaterTodayFiresToday() {
    Alarms alarms = new Alarms("[]");
    JSONObject item = create(alarms, "07:30", "once", at(2026, 11, 19, 6, 0));
    assertEquals("2026-11-19T07:30:00", item.optString("next_fire"));
    assertTrue(item.optBoolean("enabled"));
    assertTrue(item.isNull("last_fired"));
  }

  @Test public void onceAlreadyPassedGoesTomorrow() {
    Alarms alarms = new Alarms("[]");
    JSONObject item = create(alarms, "07:30", "once", at(2026, 11, 19, 8, 0));
    assertEquals("2026-11-20T07:30:00", item.optString("next_fire"));
  }

  @Test public void dailyAdvancesToNextDayAfterFiring() {
    Alarms alarms = new Alarms("[]");
    JSONObject item = create(alarms, "07:30", "daily", at(2026, 11, 19, 8, 0));
    assertEquals("2026-11-20T07:30:00", item.optString("next_fire"));
    alarms.markFired(item.optString("id"), at(2026, 11, 20, 7, 30));
    assertEquals("2026-11-21T07:30:00", item.optString("next_fire"));
    assertEquals("2026-11-20T07:30:00", item.optString("last_fired"));
    assertTrue(item.optBoolean("enabled"));
  }

  @Test public void onceDisablesItselfAfterFiring() {
    Alarms alarms = new Alarms("[]");
    JSONObject item = create(alarms, "07:30", "once", at(2026, 11, 19, 6, 0));
    alarms.markFired(item.optString("id"), at(2026, 11, 19, 7, 30));
    assertFalse(item.optBoolean("enabled"));
    assertTrue(item.isNull("next_fire"));
    assertNull(alarms.dueItem(at(2026, 11, 20, 7, 30)));
  }

  @Test public void weekdaysSkipTheWeekend() {
    Alarms alarms = new Alarms("[]");
    // Friday 08:00, today's slot has passed: next is Monday.
    JSONObject item = create(alarms, "07:30", "weekdays", at(2026, 11, 20, 8, 0));
    assertEquals("2026-11-23T07:30:00", item.optString("next_fire"));
    // Friday 06:00: still today.
    JSONObject sameDay = create(new Alarms("[]"), "07:30", "weekdays", at(2026, 11, 20, 6, 0));
    assertEquals("2026-11-20T07:30:00", sameDay.optString("next_fire"));
    // Saturday morning: Monday.
    JSONObject saturday = create(new Alarms("[]"), "07:30", "weekdays", at(2026, 11, 21, 6, 0));
    assertEquals("2026-11-23T07:30:00", saturday.optString("next_fire"));
  }

  @Test public void weeklyUsesTheGivenDaySet() {
    Alarms alarms = new Alarms("[]");
    // Wednesday 2026-11-18 08:00 with Mon/Wed/Fri: today's slot passed, next is Friday.
    JSONObject item =
        alarms.create("07:30", "weekly", days(1, 3, 5), 0, "", at(2026, 11, 18, 8, 0));
    assertEquals("2026-11-20T07:30:00", item.optString("next_fire"));
    // Wednesday 06:00: today.
    JSONObject sameDay =
        new Alarms("[]").create("07:30", "weekly", days(1, 3, 5), 0, "", at(2026, 11, 18, 6, 0));
    assertEquals("2026-11-18T07:30:00", sameDay.optString("next_fire"));
    // Friday 08:00: wraps to Monday.
    JSONObject wrap =
        new Alarms("[]").create("07:30", "weekly", days(1, 3, 5), 0, "", at(2026, 11, 20, 8, 0));
    assertEquals("2026-11-23T07:30:00", wrap.optString("next_fire"));
  }

  @Test public void monthlySkipsMonthsWithoutTheDay() {
    // February 2027 has 28 days: the 31st lands on 2027-03-31.
    JSONObject item =
        new Alarms("[]").create("07:30", "monthly", null, 31, "", at(2027, 2, 10, 12, 0));
    assertEquals("2027-03-31T07:30:00", item.optString("next_fire"));
    // Same month, still ahead: 2026-11-15 from 2026-11-10.
    JSONObject sameMonth =
        new Alarms("[]").create("07:30", "monthly", null, 15, "", at(2026, 11, 10, 12, 0));
    assertEquals("2026-11-15T07:30:00", sameMonth.optString("next_fire"));
  }

  @Test public void monthlyFeb29DependsOnLeapYear() {
    // 2026 is not a leap year: February has no 29th, next is 2026-03-29.
    JSONObject common =
        new Alarms("[]").create("07:30", "monthly", null, 29, "", at(2026, 2, 10, 12, 0));
    assertEquals("2026-03-29T07:30:00", common.optString("next_fire"));
    // 2028 is a leap year: 2028-02-29 exists.
    JSONObject leap =
        new Alarms("[]").create("07:30", "monthly", null, 29, "", at(2028, 2, 10, 12, 0));
    assertEquals("2028-02-29T07:30:00", leap.optString("next_fire"));
  }

  @Test public void setEnabledRecomputesOnReenable() {
    Alarms alarms = new Alarms("[]");
    JSONObject item = create(alarms, "07:30", "daily", at(2026, 11, 19, 6, 0));
    String id = item.optString("id");
    assertEquals("2026-11-19T07:30:00", item.optString("next_fire"));
    alarms.setEnabled(id, false, at(2026, 11, 19, 6, 5));
    assertFalse(item.optBoolean("enabled"));
    assertTrue(item.isNull("next_fire"));
    assertNull(alarms.dueItem(at(2026, 11, 19, 7, 30)));
    // Re-enabled on Friday after the slot: next fire is Saturday.
    alarms.setEnabled(id, true, at(2026, 11, 20, 8, 0));
    assertTrue(item.optBoolean("enabled"));
    assertEquals("2026-11-21T07:30:00", item.optString("next_fire"));
  }

  @Test public void cancelRemovesOnlyTheTarget() {
    Alarms alarms = new Alarms("[]");
    JSONObject first = create(alarms, "07:30", "once", at(2026, 11, 19, 6, 0));
    JSONObject second = create(alarms, "08:30", "daily", at(2026, 11, 19, 6, 0));
    alarms.cancel(first.optString("id"));
    assertEquals(1, alarms.size());
    assertEquals(second.optString("id"), alarms.items().optJSONObject(0).optString("id"));
    try {
      alarms.cancel("missing");
      fail("cancel of an unknown id must throw");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void encodeDecodeRoundTrips() throws Exception {
    Alarms alarms = new Alarms("[]");
    alarms.create("07:30", "weekly", days(5, 1, 3), 0, "起床", at(2026, 11, 19, 6, 0));
    alarms.create("22:00", "monthly", null, 15, "", at(2026, 11, 19, 6, 0));
    create(alarms, "06:45", "once", at(2026, 11, 19, 6, 0));
    String encoded = alarms.encode();
    Alarms restored = new Alarms(encoded);
    assertEquals(3, restored.size());
    assertEquals(encoded, restored.encode());
    JSONObject weekly = restored.items().getJSONObject(0);
    assertEquals("起床", weekly.optString("label"));
    // Weekdays are stored sorted and deduplicated.
    assertEquals("[1,3,5]", weekly.getJSONObject("repeat").getJSONArray("weekdays").toString());
    assertEquals("2026-11-20T07:30:00", weekly.optString("next_fire"));
  }

  @Test public void dueItemRespectsTimeAndEnabled() {
    Alarms alarms = new Alarms("[]");
    JSONObject item = create(alarms, "07:30", "once", at(2026, 11, 19, 6, 0));
    assertNull(alarms.dueItem(at(2026, 11, 19, 7, 29)));
    assertEquals(item.optString("id"), alarms.dueItem(at(2026, 11, 19, 7, 30)).optString("id"));
    assertEquals(item.optString("id"), alarms.dueItem(at(2026, 11, 19, 9, 0)).optString("id"));
  }

  @Test public void invalidArgumentsThrow() {
    Alarms alarms = new Alarms("[]");
    LocalDateTime now = at(2026, 11, 19, 6, 0);
    String[] badTimes = {"7:30", "24:00", "07:60", "", "ab:cd"};
    for (String bad : badTimes) {
      try {
        alarms.create(bad, "once", null, 0, "", now);
        fail("bad time must throw: " + bad);
      } catch (IllegalArgumentException expected) {
      }
    }
    try {
      alarms.create("07:30", "yearly", null, 0, "", now);
      fail("unknown repeat must throw");
    } catch (IllegalArgumentException expected) {
    }
    try {
      alarms.create("07:30", "weekly", null, 0, "", now);
      fail("weekly without weekdays must throw");
    } catch (IllegalArgumentException expected) {
    }
    try {
      alarms.create("07:30", "weekly", new JSONArray(), 0, "", now);
      fail("weekly with empty weekdays must throw");
    } catch (IllegalArgumentException expected) {
    }
    try {
      alarms.create("07:30", "weekly", days(0), 0, "", now);
      fail("weekday 0 must throw");
    } catch (IllegalArgumentException expected) {
    }
    try {
      alarms.create("07:30", "weekly", days(8), 0, "", now);
      fail("weekday 8 must throw");
    } catch (IllegalArgumentException expected) {
    }
    try {
      alarms.create("07:30", "monthly", null, 0, "", now);
      fail("monthly without month_day must throw");
    } catch (IllegalArgumentException expected) {
    }
    try {
      alarms.create("07:30", "monthly", null, 32, "", now);
      fail("month_day 32 must throw");
    } catch (IllegalArgumentException expected) {
    }
    assertEquals(0, alarms.size());
  }
}

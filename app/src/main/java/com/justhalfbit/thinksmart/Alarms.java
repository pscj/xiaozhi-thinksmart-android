package com.justhalfbit.thinksmart;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.*;

/**
 * Device-local alarms behind the self.alarm.* MCP tools. Pure logic (no
 * Android APIs) in the ChatLog style — items are JSONObjects, the whole list
 * encodes to one JSONArray string for a single prefs key — so the recurrence
 * matrix is unit-testable. VoiceService owns persistence (via the change
 * listener) and the per-second firing check.
 *
 * <p>Entry shape: {id, label, time "HH:MM", repeat {type, weekdays[],
 * month_day}, enabled, next_fire, last_fired, created_at}. Times are naive
 * device-local ISO strings ("2026-11-20T07:30:00"); weekdays are 1=Monday …
 * 7=Sunday. A disabled entry carries next_fire=null.
 */
final class Alarms {
  static final String TYPE_ONCE = "once",
      TYPE_DAILY = "daily",
      TYPE_WEEKDAYS = "weekdays",
      TYPE_WEEKLY = "weekly",
      TYPE_MONTHLY = "monthly";
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  private final List<JSONObject> items = new ArrayList<>();
  private Runnable changed;

  Alarms(String encoded) {
    try {
      JSONArray array = new JSONArray(encoded == null || encoded.isEmpty() ? "[]" : encoded);
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.optJSONObject(i);
        if (item != null && item.has("id") && item.has("time") && item.has("repeat"))
          items.add(item);
      }
    } catch (JSONException ignored) {
    }
  }

  void setChangedListener(Runnable listener) {
    changed = listener;
  }

  private void fireChanged() {
    if (changed != null) changed.run();
  }

  int size() {
    return items.size();
  }

  String encode() {
    return new JSONArray(items).toString();
  }

  /** Deep copy of all entries, for the list tool and tests. */
  JSONArray items() {
    try {
      return new JSONArray(encode());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  JSONObject create(
      String time, String repeatType, JSONArray weekdays, int monthDay,
      String label, LocalDateTime now) {
    Protocol.minutes(time); // validates HH:MM, throws IllegalArgumentException
    String type = repeatType == null || repeatType.isEmpty() ? TYPE_ONCE : repeatType;
    if (!type.equals(TYPE_ONCE)
        && !type.equals(TYPE_DAILY)
        && !type.equals(TYPE_WEEKDAYS)
        && !type.equals(TYPE_WEEKLY)
        && !type.equals(TYPE_MONTHLY))
      throw new IllegalArgumentException("repeat 应为 once/daily/weekdays/weekly/monthly");
    JSONArray days = normalizeWeekdays(type, weekdays);
    int day = normalizeMonthDay(type, monthDay);
    JSONObject item = new JSONObject();
    try {
      JSONObject repeat = new JSONObject();
      repeat.put("type", type);
      repeat.put("weekdays", days);
      repeat.put("month_day", day);
      item.put("id", newId());
      item.put("label", label == null ? "" : label);
      item.put("time", time);
      item.put("repeat", repeat);
      item.put("enabled", true);
      item.put("next_fire", format(computeNext(time, type, days, day, now)));
      item.put("last_fired", JSONObject.NULL);
      item.put("created_at", format(now));
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    items.add(item);
    fireChanged();
    return item;
  }

  void cancel(String id) {
    items.remove(find(id));
    fireChanged();
  }

  JSONObject setEnabled(String id, boolean enabled, LocalDateTime now) {
    JSONObject item = find(id);
    try {
      item.put("enabled", enabled);
      if (enabled) item.put("next_fire", format(nextFireOf(item, now)));
      else item.put("next_fire", JSONObject.NULL);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    fireChanged();
    return item;
  }

  /** The first enabled entry whose next_fire has been reached, or null. */
  JSONObject dueItem(LocalDateTime now) {
    for (JSONObject item : items) {
      if (!item.optBoolean("enabled") || item.isNull("next_fire")) continue;
      LocalDateTime fire = parse(item.optString("next_fire", ""));
      if (fire != null && !fire.isAfter(now)) return item;
    }
    return null;
  }

  /**
   * Record a firing: last_fired is stamped; a once alarm disables itself,
   * a recurring alarm advances to its next occurrence after {@code now}.
   */
  JSONObject markFired(String id, LocalDateTime now) {
    JSONObject item = find(id);
    try {
      item.put("last_fired", format(now));
      JSONObject repeat = item.getJSONObject("repeat");
      if (repeat.getString("type").equals(TYPE_ONCE)) {
        item.put("enabled", false);
        item.put("next_fire", JSONObject.NULL);
      } else item.put("next_fire", format(nextFireOf(item, now)));
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    fireChanged();
    return item;
  }

  private LocalDateTime nextFireOf(JSONObject item, LocalDateTime now) throws JSONException {
    JSONObject repeat = item.getJSONObject("repeat");
    return computeNext(
        item.getString("time"),
        repeat.getString("type"),
        repeat.optJSONArray("weekdays"),
        repeat.optInt("month_day"),
        now);
  }

  /**
   * First candidate at {@code time} on a rule-matching day that is strictly
   * after {@code now}. once/daily: today, else tomorrow. weekdays: Monday to
   * Friday only. weekly: the given weekday set. monthly: month_day, skipping
   * months that have no such day (at most three months ahead covers the
   * February gap for the 29th–31st).
   */
  static LocalDateTime computeNext(
      String time, String type, JSONArray days, int monthDay, LocalDateTime now) {
    int minutes = Protocol.minutes(time);
    int hour = minutes / 60, minute = minutes % 60;
    LocalDate today = now.toLocalDate();
    if (type.equals(TYPE_ONCE) || type.equals(TYPE_DAILY)) {
      LocalDateTime candidate = today.atTime(hour, minute);
      return candidate.isAfter(now) ? candidate : candidate.plusDays(1);
    }
    if (type.equals(TYPE_WEEKDAYS) || type.equals(TYPE_WEEKLY)) {
      Set<Integer> wanted = new HashSet<>();
      if (type.equals(TYPE_WEEKDAYS)) {
        for (int d = 1; d <= 5; d++) wanted.add(d);
      } else if (days != null) {
        for (int i = 0; i < days.length(); i++) wanted.add(days.optInt(i));
      }
      for (int offset = 0; offset <= 7; offset++) {
        LocalDate date = today.plusDays(offset);
        if (!wanted.contains(date.getDayOfWeek().getValue())) continue;
        LocalDateTime candidate = date.atTime(hour, minute);
        if (candidate.isAfter(now)) return candidate;
      }
      throw new IllegalStateException("无法计算下一次触发");
    }
    if (type.equals(TYPE_MONTHLY)) {
      YearMonth month = YearMonth.from(today);
      for (int offset = 0; offset <= 3; offset++) {
        YearMonth ym = month.plusMonths(offset);
        if (monthDay > ym.lengthOfMonth()) continue;
        LocalDateTime candidate = ym.atDay(monthDay).atTime(hour, minute);
        if (candidate.isAfter(now)) return candidate;
      }
      throw new IllegalStateException("无法计算下一次触发");
    }
    throw new IllegalArgumentException("未知循环类型 " + type);
  }

  private static JSONArray normalizeWeekdays(String type, JSONArray weekdays) {
    JSONArray days = new JSONArray();
    if (!type.equals(TYPE_WEEKLY)) return days;
    if (weekdays == null || weekdays.length() == 0)
      throw new IllegalArgumentException("weekly 需要提供 weekdays（1=周一…7=周日）");
    TreeSet<Integer> sorted = new TreeSet<>();
    for (int i = 0; i < weekdays.length(); i++) {
      int d = weekdays.optInt(i, -1);
      if (d < 1 || d > 7) throw new IllegalArgumentException("weekdays 只能是 1-7（1=周一…7=周日）");
      sorted.add(d);
    }
    for (int d : sorted) days.put(d);
    return days;
  }

  private static int normalizeMonthDay(String type, int monthDay) {
    if (!type.equals(TYPE_MONTHLY)) return 0;
    if (monthDay < 1 || monthDay > 31)
      throw new IllegalArgumentException("monthly 需要提供 month_day（1-31）");
    return monthDay;
  }

  private JSONObject find(String id) {
    for (JSONObject item : items) if (item.optString("id").equals(id)) return item;
    throw new IllegalArgumentException("闹钟不存在：" + id);
  }

  private String newId() {
    String id;
    do {
      id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    } while (idTaken(id));
    return id;
  }

  private boolean idTaken(String id) {
    for (JSONObject item : items) if (item.optString("id").equals(id)) return true;
    return false;
  }

  static String format(LocalDateTime value) {
    return ISO.format(value.withNano(0));
  }

  static LocalDateTime parse(String text) {
    if (text == null || text.isEmpty()) return null;
    try {
      return LocalDateTime.parse(text, ISO);
    } catch (RuntimeException e) {
      return null;
    }
  }
}

package com.justhalfbit.thinksmart;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.*;

/**
 * Device-local countdown timers behind the self.timer.* MCP tools. Pure
 * logic (no Android APIs) in the Alarms style — items are JSONObjects, the
 * whole list encodes to one JSONArray string for a single prefs key — so
 * the state machine is unit-testable. VoiceService owns persistence (via
 * the change listener) and the per-second firing check.
 *
 * <p>Entry shape: {id, label, duration_sec, state, ends_at, remaining_sec,
 * created_at}. Times are epoch milliseconds: a countdown is elapsed-time
 * semantics ("ten minutes from now"), so ends_at is stored once and the
 * remaining seconds are always derived, never decremented — tick jitter
 * and process restarts cannot drift it. A running entry carries
 * ends_at=&lt;millis&gt; and remaining_sec=null; pausing freezes
 * remaining_sec and clears ends_at. A timer whose ends_at has passed is
 * due, including right after a restart (the service re-fires it on its
 * first tick: an overdue timer rings once instead of vanishing).
 *
 * <p>Methods are synchronized: besides the MCP/io threads, the activity
 * reads display snapshots on the UI thread.
 */
final class Timers {
  static final String STATE_RUNNING = "running", STATE_PAUSED = "paused";
  static final int MIN_DURATION_SEC = 1, MAX_DURATION_SEC = 86400;
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  private final List<JSONObject> items = new ArrayList<>();
  private Runnable changed;

  Timers(String encoded) {
    try {
      JSONArray array = new JSONArray(encoded == null || encoded.isEmpty() ? "[]" : encoded);
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.optJSONObject(i);
        if (item != null && item.has("id") && item.has("duration_sec") && item.has("state"))
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

  synchronized int size() {
    return items.size();
  }

  synchronized String encode() {
    return new JSONArray(items).toString();
  }

  /** Deep copy of all entries, each enriched with derived fields (see snapshot). */
  synchronized JSONArray items(long nowMs) {
    return snapshot(nowMs);
  }

  /**
   * Deep copy of all entries with the derived values filled in at {@code
   * nowMs}: remaining_sec (present on every entry here, unlike storage)
   * and ends_at_local for running entries.
   */
  synchronized JSONArray snapshot(long nowMs) {
    JSONArray out = new JSONArray();
    for (JSONObject item : items) out.put(copyWithDerived(item, nowMs));
    return out;
  }

  synchronized JSONObject create(int durationSec, String label, long nowMs) {
    if (durationSec < MIN_DURATION_SEC || durationSec > MAX_DURATION_SEC)
      throw new IllegalArgumentException("duration_sec 应为 1…86400（最长 24 小时）");
    JSONObject item = new JSONObject();
    try {
      item.put("id", newId());
      item.put("label", label == null ? "" : label);
      item.put("duration_sec", durationSec);
      item.put("state", STATE_RUNNING);
      item.put("ends_at", nowMs + durationSec * 1000L);
      item.put("remaining_sec", JSONObject.NULL);
      item.put("created_at", nowMs);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    items.add(item);
    fireChanged();
    return item;
  }

  synchronized void cancel(String id) {
    items.remove(find(id));
    fireChanged();
  }

  /** Remove without the unknown-id error, for the firing path. */
  synchronized void remove(String id) {
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).optString("id").equals(id)) {
        items.remove(i);
        fireChanged();
        return;
      }
    }
  }

  /** Freeze a running timer: remaining seconds are stored, ends_at cleared. */
  synchronized JSONObject pause(String id, long nowMs) {
    JSONObject item = find(id);
    if (!STATE_RUNNING.equals(item.optString("state")))
      throw new IllegalArgumentException("只有运行中的倒计时可以暂停");
    try {
      item.put("remaining_sec", remainingSec(item, nowMs));
      item.put("ends_at", JSONObject.NULL);
      item.put("state", STATE_PAUSED);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    fireChanged();
    return item;
  }

  /** Resume a paused timer: a fresh ends_at is computed from the frozen rest. */
  synchronized JSONObject resume(String id, long nowMs) {
    JSONObject item = find(id);
    if (!STATE_PAUSED.equals(item.optString("state")))
      throw new IllegalArgumentException("只有已暂停的倒计时可以继续");
    try {
      item.put("ends_at", nowMs + item.optLong("remaining_sec", 0) * 1000L);
      item.put("remaining_sec", JSONObject.NULL);
      item.put("state", STATE_RUNNING);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    fireChanged();
    return item;
  }

  /**
   * The running entry whose ends_at has been reached (the earliest such),
   * or null. Paused entries are never due. An entry that expired while the
   * process was dead is returned on the first call after restart.
   */
  synchronized JSONObject dueItem(long nowMs) {
    JSONObject best = null;
    for (JSONObject item : items) {
      if (!STATE_RUNNING.equals(item.optString("state"))) continue;
      long endsAt = item.optLong("ends_at", Long.MAX_VALUE);
      if (endsAt > nowMs) continue;
      if (best == null || endsAt < best.optLong("ends_at", Long.MAX_VALUE)) best = item;
    }
    return best;
  }

  /**
   * The entry the countdown screen shows: the running entry ending soonest,
   * else the paused entry with the least time left, else null.
   */
  synchronized JSONObject displayItem(long nowMs) {
    JSONObject best = null;
    for (JSONObject item : items) {
      if (!STATE_RUNNING.equals(item.optString("state"))) continue;
      if (best == null
          || item.optLong("ends_at", Long.MAX_VALUE) < best.optLong("ends_at", Long.MAX_VALUE))
        best = item;
    }
    if (best != null) return best;
    for (JSONObject item : items) {
      if (!STATE_PAUSED.equals(item.optString("state"))) continue;
      if (best == null
          || item.optLong("remaining_sec", Long.MAX_VALUE)
              < best.optLong("remaining_sec", Long.MAX_VALUE))
        best = item;
    }
    return best;
  }

  /** Copy of the display entry with derived fields, or null when there is none. */
  synchronized JSONObject displayCopy(long nowMs) {
    JSONObject item = displayItem(nowMs);
    return item == null ? null : copyWithDerived(item, nowMs);
  }

  /** Copy of the entry that would be displayed if {@code excludeId} were gone. */
  synchronized JSONObject runnerUpCopy(long nowMs, String excludeId) {
    JSONObject best = null;
    for (JSONObject item : items) {
      if (item.optString("id").equals(excludeId)) continue;
      if (!STATE_RUNNING.equals(item.optString("state"))) continue;
      if (best == null
          || item.optLong("ends_at", Long.MAX_VALUE) < best.optLong("ends_at", Long.MAX_VALUE))
        best = item;
    }
    if (best == null) {
      for (JSONObject item : items) {
        if (item.optString("id").equals(excludeId)) continue;
        if (!STATE_PAUSED.equals(item.optString("state"))) continue;
        if (best == null
            || item.optLong("remaining_sec", Long.MAX_VALUE)
                < best.optLong("remaining_sec", Long.MAX_VALUE))
          best = item;
      }
    }
    return best == null ? null : copyWithDerived(best, nowMs);
  }

  /** Remaining whole seconds at {@code nowMs}, rounding up; paused entries are frozen. */
  static long remainingSec(JSONObject item, long nowMs) {
    if (STATE_PAUSED.equals(item.optString("state")))
      return Math.max(0, item.optLong("remaining_sec", 0));
    long diff = item.optLong("ends_at", 0) - nowMs;
    if (diff <= 0) return 0;
    return (diff + 999) / 1000;
  }

  private JSONObject copyWithDerived(JSONObject item, long nowMs) {
    try {
      JSONObject copy = new JSONObject(item.toString());
      copy.put("remaining_sec", remainingSec(item, nowMs));
      if (STATE_RUNNING.equals(item.optString("state")))
        copy.put("ends_at_local", localIso(item.optLong("ends_at", 0)));
      else copy.put("ends_at_local", JSONObject.NULL);
      return copy;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private JSONObject find(String id) {
    for (JSONObject item : items) if (item.optString("id").equals(id)) return item;
    throw new IllegalArgumentException("倒计时不存在：" + id);
  }

  private String newId() {
    String id;
    do {
      id = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
    } while (idTaken(id));
    return id;
  }

  private boolean idTaken(String id) {
    for (JSONObject item : items) if (item.optString("id").equals(id)) return true;
    return false;
  }

  static String localIso(long epochMs) {
    return ISO.format(
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()));
  }
}

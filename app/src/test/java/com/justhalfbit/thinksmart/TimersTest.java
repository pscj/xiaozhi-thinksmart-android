package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class TimersTest {
  private static final long T0 = 1_763_000_000_000L;

  @Test public void createSetsEndsAtAndRunningState() {
    Timers timers = new Timers("[]");
    JSONObject item = timers.create(600, "煮面", T0);
    assertEquals(600, item.optInt("duration_sec"));
    assertEquals("煮面", item.optString("label"));
    assertEquals("running", item.optString("state"));
    assertEquals(T0 + 600_000L, item.optLong("ends_at"));
    assertTrue(item.isNull("remaining_sec"));
    assertEquals(T0, item.optLong("created_at"));
    assertEquals(1, timers.size());
    // Ids carry the timer prefix so logs can tell them from alarm ids.
    assertTrue(item.optString("id").startsWith("t"));
  }

  @Test public void createValidatesDurationRange() {
    Timers timers = new Timers("[]");
    int[] bad = {0, -1, -600, 86401, Integer.MAX_VALUE};
    for (int duration : bad) {
      try {
        timers.create(duration, "", T0);
        fail("out-of-range duration must throw: " + duration);
      } catch (IllegalArgumentException expected) {
      }
    }
    assertEquals(0, timers.size());
    timers.create(1, "", T0);
    timers.create(86400, "", T0);
    assertEquals(2, timers.size());
  }

  @Test public void remainingCountsDownRoundingUp() {
    Timers timers = new Timers("[]");
    JSONObject item = timers.create(600, "", T0);
    assertEquals(600, Timers.remainingSec(item, T0));
    // One millisecond in, the display still shows the full minute count.
    assertEquals(600, Timers.remainingSec(item, T0 + 1));
    assertEquals(599, Timers.remainingSec(item, T0 + 1000));
    assertEquals(1, Timers.remainingSec(item, T0 + 599_500L));
    assertEquals(0, Timers.remainingSec(item, T0 + 600_000L));
    // Long past the end it stays at zero, never negative.
    assertEquals(0, Timers.remainingSec(item, T0 + 900_000L));
  }

  @Test public void pauseFreezesRemaining() {
    Timers timers = new Timers("[]");
    JSONObject item = timers.create(600, "", T0);
    String id = item.optString("id");
    timers.pause(id, T0 + 61_000L);
    assertEquals("paused", item.optString("state"));
    assertEquals(539, item.optLong("remaining_sec"));
    assertTrue(item.isNull("ends_at"));
    // Wall-clock time passing does not move a paused timer.
    assertEquals(539, Timers.remainingSec(item, T0 + 3_600_000L));
    assertEquals(539, timers.snapshot(T0 + 3_600_000L).optJSONObject(0).optLong("remaining_sec"));
  }

  @Test public void resumeRecomputesEndsAtFromFrozenRest() {
    Timers timers = new Timers("[]");
    JSONObject item = timers.create(600, "", T0);
    String id = item.optString("id");
    timers.pause(id, T0 + 61_000L);
    long resumeAt = T0 + 3_600_000L;
    timers.resume(id, resumeAt);
    assertEquals("running", item.optString("state"));
    assertEquals(resumeAt + 539_000L, item.optLong("ends_at"));
    assertTrue(item.isNull("remaining_sec"));
    assertEquals(539, Timers.remainingSec(item, resumeAt));
  }

  @Test public void pauseResumeRejectWrongStateAndUnknownId() {
    Timers timers = new Timers("[]");
    JSONObject item = timers.create(600, "", T0);
    String id = item.optString("id");
    try {
      timers.resume(id, T0);
      fail("resume of a running timer must throw");
    } catch (IllegalArgumentException expected) {
    }
    timers.pause(id, T0);
    try {
      timers.pause(id, T0);
      fail("pause of a paused timer must throw");
    } catch (IllegalArgumentException expected) {
    }
    try {
      timers.pause("missing", T0);
      fail("pause of an unknown id must throw");
    } catch (IllegalArgumentException expected) {
    }
    try {
      timers.cancel("missing");
      fail("cancel of an unknown id must throw");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void cancelRemovesOnlyTheTarget() {
    Timers timers = new Timers("[]");
    JSONObject first = timers.create(300, "a", T0);
    JSONObject second = timers.create(900, "b", T0);
    timers.cancel(first.optString("id"));
    assertEquals(1, timers.size());
    assertEquals(
        second.optString("id"), timers.snapshot(T0).optJSONObject(0).optString("id"));
  }

  @Test public void dueItemPicksTheEarliestExpiredRunning() {
    Timers timers = new Timers("[]");
    JSONObject later = timers.create(900, "later", T0);
    JSONObject sooner = timers.create(300, "sooner", T0);
    assertNull(timers.dueItem(T0 + 299_000L));
    assertEquals(sooner.optString("id"), timers.dueItem(T0 + 300_000L).optString("id"));
    assertEquals(sooner.optString("id"), timers.dueItem(T0 + 950_000L).optString("id"));
    // A paused timer is never due, even past its would-be end.
    timers.pause(later.optString("id"), T0);
    timers.remove(sooner.optString("id"));
    assertNull(timers.dueItem(T0 + 950_000L));
  }

  @Test public void expiredRunningTimerIsDueAfterRestart() {
    // A timer set for 5 minutes; the process dies and comes back 10
    // minutes later. Decoding the persisted form (the restart path) must
    // surface it as due at once, so the service re-fires it on its first
    // tick instead of silently dropping it.
    Timers timers = new Timers("[]");
    JSONObject item = timers.create(300, "煮面", T0);
    timers.pause(timers.create(120, "泡茶", T0).optString("id"), T0);
    Timers restored = new Timers(timers.encode());
    long after = T0 + 600_000L;
    JSONObject due = restored.dueItem(after);
    assertNotNull(due);
    assertEquals(item.optString("id"), due.optString("id"));
    // The paused entry survives the restart untouched and undue; once the
    // re-fired entry is consumed (as the firing path does), it is what
    // the screen would show.
    assertEquals(2, restored.size());
    restored.remove(due.optString("id"));
    JSONObject paused = restored.displayCopy(after);
    assertEquals("泡茶", paused.optString("label"));
    assertEquals(120, paused.optLong("remaining_sec"));
  }

  @Test public void encodeDecodeRoundTrips() {
    Timers timers = new Timers("[]");
    timers.create(600, "煮面", T0);
    JSONObject paused = timers.create(120, "", T0);
    timers.pause(paused.optString("id"), T0 + 30_000L);
    String encoded = timers.encode();
    Timers restored = new Timers(encoded);
    assertEquals(2, restored.size());
    assertEquals(encoded, restored.encode());
  }

  @Test public void displayItemPrefersSoonestRunningThenPaused() {
    Timers timers = new Timers("[]");
    assertNull(timers.displayItem(T0));
    JSONObject paused = timers.create(120, "paused", T0);
    timers.pause(paused.optString("id"), T0);
    // Only a paused timer: it is what the screen shows.
    assertEquals(paused.optString("id"), timers.displayItem(T0).optString("id"));
    JSONObject later = timers.create(900, "later", T0);
    JSONObject sooner = timers.create(300, "sooner", T0);
    assertEquals(sooner.optString("id"), timers.displayItem(T0).optString("id"));
    // The runner-up is the next one in line once the display entry is gone.
    JSONObject runnerUp = timers.runnerUpCopy(T0, sooner.optString("id"));
    assertEquals(later.optString("id"), runnerUp.optString("id"));
  }

  @Test public void snapshotCarriesDerivedRemainingAndLocalEnd() throws Exception {
    Timers timers = new Timers("[]");
    timers.create(600, "煮面", T0);
    JSONObject entry = timers.snapshot(T0 + 61_000L).optJSONObject(0);
    assertEquals(539, entry.optLong("remaining_sec"));
    assertEquals(Timers.localIso(T0 + 600_000L), entry.optString("ends_at_local"));
    // The stored form itself never carries a stale remaining value.
    JSONObject stored = new JSONArray(timers.encode()).getJSONObject(0);
    assertTrue(stored.isNull("remaining_sec"));
  }
}

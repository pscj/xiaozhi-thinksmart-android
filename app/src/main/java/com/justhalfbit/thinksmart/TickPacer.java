package com.justhalfbit.thinksmart;

/**
 * Cadence for MainActivity's per-second tick. Pure logic, mirroring
 * CountdownGate and CountdownExemption.
 *
 * <p>The tick used to re-arm itself with a fixed postDelayed(1000) at the
 * end of run(), so every period was 1000 ms plus the run's own work
 * (clock, brightness, ambient upkeep — not cheap on this hardware) plus
 * scheduling slop. The phase drifted a little further every second, and
 * whenever the accumulated drift carried a render across the boundary of
 * the countdown second it happened to be displaying, that value was
 * never shown: the digits skipped (58 to 55 after a stall). This pacer
 * instead keeps a planned instant and delays only until it, so work and
 * slop are absorbed each round instead of accumulated. A stall of more
 * than a full period re-anchors the phase rather than bursting to catch
 * up.
 *
 * <p>While the countdown screen shows a running timer it can do better
 * than a fixed phase: the displayed remaining seconds are
 * ceil((ends_at - now) / 1000), so the value flips exactly when
 * ends_at - now crosses a multiple of 1000 — on ends_at's own
 * millisecond phase, not on wall-clock second boundaries. flipDelayMs
 * gives the delay that lands the next render just after the next flip,
 * so each value gets a full beat on screen.
 */
final class TickPacer {
  static final long PERIOD_MS = 1000;
  /** How long after a countdown second flip the render should land. */
  static final long FLIP_OFFSET_MS = 50;

  /** Next planned tick instant on the caller's monotonic clock; -1 = unset. */
  private long plannedMs = -1;

  /**
   * Delay from nowMs (monotonic clock) until the next tick, keeping the
   * planned phase: a late tick shortens the following delay instead of
   * shifting every later tick. More than a full period late re-anchors
   * the phase at nowMs instead of firing repeatedly to catch up.
   */
  long nextDelayMs(long nowMs) {
    if (plannedMs < 0) plannedMs = nowMs;
    if (nowMs - plannedMs > PERIOD_MS) {
      plannedMs = nowMs + PERIOD_MS;
      return PERIOD_MS;
    }
    plannedMs += PERIOD_MS;
    return Math.max(0, plannedMs - nowMs);
  }

  /** Drop the planned phase; the next nextDelayMs call re-anchors. */
  void reset() {
    plannedMs = -1;
  }

  /**
   * Delay from nowWallMs so the render lands FLIP_OFFSET_MS after the
   * next flip of a running timer ending at endsAtWallMs (both wall
   * clock). Always in (0, PERIOD_MS + FLIP_OFFSET_MS]; -1 when the
   * timer has already ended.
   */
  static long flipDelayMs(long nowWallMs, long endsAtWallMs) {
    long diff = endsAtWallMs - nowWallMs;
    if (diff <= 0) return -1;
    long intoSecond = diff % PERIOD_MS;
    long untilFlip = intoSecond == 0 ? PERIOD_MS : intoSecond;
    return untilFlip + FLIP_OFFSET_MS;
  }
}

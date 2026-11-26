package com.justhalfbit.thinksmart;

/**
 * Shared timeline for the info screen's news titles. Pure logic, mirroring
 * TickPacer and CountdownGate.
 *
 * <p>The titles used to run the framework marquee, which scrolls every row
 * on its own schedule: rows start together but drift apart at once, because
 * each row's trip lasts its own overflow, so a short title finishes,
 * rewinds and restarts while a long one is still travelling. The user asked
 * for the rows to move as one group instead. This clock is the single
 * timeline every row follows:
 *
 * <ol>
 *   <li>hold at the start for {@link #START_PAUSE_MS}, heads showing;</li>
 *   <li>scroll left at a constant speed until the shared offset reaches the
 *       group's largest overflow — a row whose own overflow is smaller
 *       stops at its tail (see {@link #rowOffsetPx}) and waits there;</li>
 *   <li>hold at the end for {@link #END_PAUSE_MS}, tails showing;</li>
 *   <li>wrap to zero and repeat.</li>
 * </ol>
 *
 * <p>The offset is a pure function of the elapsed time inside the cycle,
 * so every row derives its position from the same elapsed reading and the
 * group cannot drift apart, however irregular the driver's ticks are.
 */
final class NewsScrollClock {

  /** Beat at the start of a cycle, titles at rest showing their heads. */
  static final long START_PAUSE_MS = 1500;

  /** Beat at the end of a cycle, titles parked showing their tails. */
  static final long END_PAUSE_MS = 2000;

  private NewsScrollClock() {}

  /** How long the scroll phase lasts: the widest overflow at the shared speed. */
  static long scrollDurationMs(int maxOverflowPx, int speedPxPerSec) {
    if (maxOverflowPx <= 0 || speedPxPerSec <= 0) return 0;
    return (maxOverflowPx * 1000L + speedPxPerSec - 1) / speedPxPerSec;
  }

  /** Full cycle length; 0 when nothing overflows (no cycle to run). */
  static long cycleLengthMs(int maxOverflowPx, int speedPxPerSec) {
    if (maxOverflowPx <= 0 || speedPxPerSec <= 0) return 0;
    return START_PAUSE_MS + scrollDurationMs(maxOverflowPx, speedPxPerSec) + END_PAUSE_MS;
  }

  /**
   * The group's shared offset at elapsedMs after the cycle anchor. Wraps
   * every cycle, so a driver can feed it an ever-growing elapsed reading.
   */
  static int sharedOffsetPx(long elapsedMs, int maxOverflowPx, int speedPxPerSec) {
    long cycle = cycleLengthMs(maxOverflowPx, speedPxPerSec);
    if (cycle == 0 || elapsedMs <= 0) return 0;
    long t = elapsedMs % cycle;
    if (t < START_PAUSE_MS) return 0;
    long scrolled = (t - START_PAUSE_MS) * speedPxPerSec / 1000;
    return (int) Math.min(maxOverflowPx, scrolled);
  }

  /**
   * One row's offset: it follows the shared offset but never past its own
   * overflow, so a short title parks at its tail while the group travels on.
   */
  static int rowOffsetPx(int sharedOffsetPx, int rowOverflowPx) {
    if (sharedOffsetPx <= 0 || rowOverflowPx <= 0) return 0;
    return Math.min(sharedOffsetPx, rowOverflowPx);
  }
}

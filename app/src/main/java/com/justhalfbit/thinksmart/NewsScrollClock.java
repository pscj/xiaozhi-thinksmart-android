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
 *   <li>scroll left at a constant speed. A row's text is drawn on a loop of
 *       its own text width plus a blank gap (see {@link #loopLengthPx}), so
 *       a row that reaches its tail does not stop there: it keeps rolling,
 *       its head comes back in from the right, and once the head is aligned
 *       at the left again the row parks there and waits for the group. The
 *       phase ends when the shared travel reaches the longest row's full
 *       loop, so by then every row is back at its head;</li>
 *   <li>hold at the end for {@link #END_PAUSE_MS}, all rows parked at their
 *       heads;</li>
 *   <li>wrap to zero and repeat — no row ever jumps.</li>
 * </ol>
 *
 * <p>The travel is a pure function of the elapsed time inside the cycle,
 * so every row derives its position from the same elapsed reading and the
 * group cannot drift apart, however irregular the driver's ticks are.
 */
final class NewsScrollClock {

  /** Beat at the start of a cycle, titles at rest showing their heads. */
  static final long START_PAUSE_MS = 1500;

  /** Beat at the end of a cycle, titles parked showing their heads. */
  static final long END_PAUSE_MS = 2000;

  private NewsScrollClock() {}

  /**
   * One row's full loop length: its text width plus the blank gap before
   * the head comes round again. 0 when the text fits the row — such a row
   * never moves.
   */
  static int loopLengthPx(int textWidthPx, int viewWidthPx, int gapPx) {
    if (textWidthPx <= viewWidthPx) return 0;
    return textWidthPx + Math.max(0, gapPx);
  }

  /** How long the scroll phase lasts: the longest loop at the shared speed. */
  static long scrollDurationMs(int maxLoopPx, int speedPxPerSec) {
    if (maxLoopPx <= 0 || speedPxPerSec <= 0) return 0;
    return (maxLoopPx * 1000L + speedPxPerSec - 1) / speedPxPerSec;
  }

  /** Full cycle length; 0 when nothing overflows (no cycle to run). */
  static long cycleLengthMs(int maxLoopPx, int speedPxPerSec) {
    if (maxLoopPx <= 0 || speedPxPerSec <= 0) return 0;
    return START_PAUSE_MS + scrollDurationMs(maxLoopPx, speedPxPerSec) + END_PAUSE_MS;
  }

  /**
   * The group's shared travel at elapsedMs after the cycle anchor: 0
   * during the start pause, then growing at the shared speed and clamped
   * at the longest row's loop length for the rest of the cycle. Wraps
   * every cycle, so a driver can feed it an ever-growing elapsed reading.
   */
  static int sharedOffsetPx(long elapsedMs, int maxLoopPx, int speedPxPerSec) {
    long cycle = cycleLengthMs(maxLoopPx, speedPxPerSec);
    if (cycle == 0 || elapsedMs <= 0) return 0;
    long t = elapsedMs % cycle;
    if (t < START_PAUSE_MS) return 0;
    long scrolled = (t - START_PAUSE_MS) * speedPxPerSec / 1000;
    return (int) Math.min(maxLoopPx, scrolled);
  }

  /**
   * One row's draw offset: it follows the shared travel but never past its
   * own loop length, taken modulo that length — so a row rolls through its
   * tail, wraps, and the moment its travel reaches the full loop the
   * offset lands back on 0: the row is parked at its head while the group
   * travels on. A row that fits (loop length 0) never moves.
   */
  static int rowOffsetPx(int sharedOffsetPx, int rowLoopPx) {
    if (sharedOffsetPx <= 0 || rowLoopPx <= 0) return 0;
    return Math.min(sharedOffsetPx, rowLoopPx) % rowLoopPx;
  }
}

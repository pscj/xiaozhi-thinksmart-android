package com.justhalfbit.thinksmart;

/**
 * Whether the countdown screen may be up. Pure logic, mirroring KeywordHit.
 *
 * <p>The screen replaces the standby screen while a timer exists, but it
 * yields to the hard blocks — an open overlay, the black curtain (sleep
 * window included), the ringing layer — and to a tap dismissal, which holds
 * until a different timer becomes the display entry. It also yields to a
 * conversation turn in flight, with one exception: an item's first
 * appearance is never blocked by a turn, so the turn that creates a timer
 * does not hide the new screen behind its own confirmation reply. Once an
 * item has appeared, later turns hide it again until they end.
 */
final class CountdownGate {
  private CountdownGate() {}

  static boolean shouldShow(
      boolean hasItem,
      boolean turnInFlight,
      boolean firstAppearance,
      boolean overlayOpen,
      boolean black,
      boolean ringing,
      boolean sleepWindow,
      boolean dismissed) {
    if (!hasItem || dismissed) return false;
    if (overlayOpen || black || ringing || sleepWindow) return false;
    return !turnInFlight || firstAppearance;
  }
}

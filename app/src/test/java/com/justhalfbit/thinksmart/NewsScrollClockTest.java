package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;

import org.junit.Test;

public class NewsScrollClockTest {

  // Reference cycle used by most tests: 300 px of overflow at 100 px/s
  // scrolls for 3000 ms, so a cycle is 1500 + 3000 + 2000 = 6500 ms.
  private static final int OVERFLOW = 300;
  private static final int SPEED = 100;

  @Test
  public void noOverflowMeansNoCycleAndNoOffset() {
    assertEquals(0, NewsScrollClock.cycleLengthMs(0, SPEED));
    assertEquals(0, NewsScrollClock.sharedOffsetPx(123456, 0, SPEED));
    assertEquals(0, NewsScrollClock.sharedOffsetPx(123456, OVERFLOW, 0));
  }

  @Test
  public void startPauseHoldsEveryRowAtItsHead() {
    assertEquals(0, NewsScrollClock.sharedOffsetPx(0, OVERFLOW, SPEED));
    assertEquals(0, NewsScrollClock.sharedOffsetPx(1499, OVERFLOW, SPEED));
  }

  @Test
  public void scrollIsLinearAtTheSharedSpeed() {
    assertEquals(50, NewsScrollClock.sharedOffsetPx(2000, OVERFLOW, SPEED));
    assertEquals(299, NewsScrollClock.sharedOffsetPx(4499, OVERFLOW, SPEED));
    assertEquals(OVERFLOW, NewsScrollClock.sharedOffsetPx(4500, OVERFLOW, SPEED));
  }

  @Test
  public void endPauseHoldsEveryRowAtItsTail() {
    assertEquals(OVERFLOW, NewsScrollClock.sharedOffsetPx(5000, OVERFLOW, SPEED));
    assertEquals(OVERFLOW, NewsScrollClock.sharedOffsetPx(6499, OVERFLOW, SPEED));
  }

  @Test
  public void cycleWrapsBackToTheHeads() {
    assertEquals(0, NewsScrollClock.sharedOffsetPx(6500, OVERFLOW, SPEED));
    assertEquals(0, NewsScrollClock.sharedOffsetPx(6500 + 1499, OVERFLOW, SPEED));
    assertEquals(50, NewsScrollClock.sharedOffsetPx(6500 + 2000, OVERFLOW, SPEED));
  }

  @Test
  public void shortRowParksAtItsOwnTailWhileTheGroupTravelsOn() {
    // The user's rule: a row that finishes early waits at its tail for the
    // longest row instead of rewinding on its own schedule.
    assertEquals(120, NewsScrollClock.rowOffsetPx(200, 120));
    assertEquals(50, NewsScrollClock.rowOffsetPx(50, 120));
    assertEquals(0, NewsScrollClock.rowOffsetPx(200, 0));
    assertEquals(0, NewsScrollClock.rowOffsetPx(0, 120));
  }

  @Test
  public void scrollDurationRoundsUpSoTheTailIsFullyReached() {
    assertEquals(2500, NewsScrollClock.scrollDurationMs(250, SPEED));
    assertEquals(2510, NewsScrollClock.scrollDurationMs(251, SPEED));
  }
}

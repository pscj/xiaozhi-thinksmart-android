package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;

import org.junit.Test;

public class NewsScrollClockTest {

  // Reference cycle used by most tests: the longest row's text is 300 px in
  // a 100 px view with a 48 px loop gap, so its loop is 348 px; at 100 px/s
  // the scroll phase lasts 3480 ms and a cycle is 1500 + 3480 + 2000 = 6980.
  private static final int LOOP = 348;
  private static final int SPEED = 100;
  private static final int GAP = 48;

  @Test
  public void loopLengthIsTextPlusGapOnlyWhenTheTextOverflows() {
    assertEquals(348, NewsScrollClock.loopLengthPx(300, 100, GAP));
    assertEquals(0, NewsScrollClock.loopLengthPx(100, 100, GAP));
    assertEquals(0, NewsScrollClock.loopLengthPx(80, 100, GAP));
  }

  @Test
  public void noLoopMeansNoCycleAndNoOffset() {
    assertEquals(0, NewsScrollClock.cycleLengthMs(0, SPEED));
    assertEquals(0, NewsScrollClock.sharedOffsetPx(123456, 0, SPEED));
    assertEquals(0, NewsScrollClock.sharedOffsetPx(123456, LOOP, 0));
  }

  @Test
  public void startPauseHoldsEveryRowAtItsHead() {
    assertEquals(0, NewsScrollClock.sharedOffsetPx(0, LOOP, SPEED));
    assertEquals(0, NewsScrollClock.sharedOffsetPx(1499, LOOP, SPEED));
  }

  @Test
  public void scrollIsLinearAtTheSharedSpeed() {
    assertEquals(50, NewsScrollClock.sharedOffsetPx(2000, LOOP, SPEED));
    assertEquals(347, NewsScrollClock.sharedOffsetPx(4979, LOOP, SPEED));
    assertEquals(LOOP, NewsScrollClock.sharedOffsetPx(4980, LOOP, SPEED));
  }

  @Test
  public void endPauseParksTheLongestRowBackAtItsHead() {
    // The shared travel sits at the full loop through the end pause, and a
    // row whose travel reaches its own loop draws at offset 0: parked at
    // its head, not its tail (the user's 2026-11-26 rule).
    int shared = NewsScrollClock.sharedOffsetPx(6000, LOOP, SPEED);
    assertEquals(LOOP, shared);
    assertEquals(0, NewsScrollClock.rowOffsetPx(shared, LOOP));
    assertEquals(0, NewsScrollClock.rowOffsetPx(LOOP, LOOP));
  }

  @Test
  public void cycleWrapsBackToTheHeads() {
    assertEquals(0, NewsScrollClock.sharedOffsetPx(6980, LOOP, SPEED));
    assertEquals(0, NewsScrollClock.sharedOffsetPx(6980 + 1499, LOOP, SPEED));
    assertEquals(50, NewsScrollClock.sharedOffsetPx(6980 + 2000, LOOP, SPEED));
  }

  @Test
  public void shortRowRollsPastItsTailAndWaitsAtItsHead() {
    // Short row: 200 px of text in a 100 px view — tail reached at a
    // travel of 100 (its overflow), full loop is 248.
    int shortLoop = NewsScrollClock.loopLengthPx(200, 100, GAP);
    assertEquals(248, shortLoop);
    // Still on its first pass: tail fully shown at its own overflow.
    assertEquals(100, NewsScrollClock.rowOffsetPx(100, shortLoop));
    // Past the tail it keeps rolling — the draw offset runs on through the
    // gap while the head comes back in from the right.
    assertEquals(200, NewsScrollClock.rowOffsetPx(200, shortLoop));
    // Head realigned at the left: parked there while the group travels on.
    assertEquals(0, NewsScrollClock.rowOffsetPx(248, shortLoop));
    assertEquals(0, NewsScrollClock.rowOffsetPx(LOOP, shortLoop));
  }

  @Test
  public void rowThatFitsNeverMoves() {
    assertEquals(0, NewsScrollClock.rowOffsetPx(LOOP, 0));
    assertEquals(0, NewsScrollClock.rowOffsetPx(0, 248));
  }

  @Test
  public void scrollDurationRoundsUpSoTheHeadFullyReturns() {
    assertEquals(3480, NewsScrollClock.scrollDurationMs(348, SPEED));
    assertEquals(3490, NewsScrollClock.scrollDurationMs(349, SPEED));
  }
}

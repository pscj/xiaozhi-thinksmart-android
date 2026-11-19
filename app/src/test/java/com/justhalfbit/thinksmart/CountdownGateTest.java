package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;

import org.junit.Test;

public class CountdownGateTest {
  // Argument order: hasItem, turnInFlight, firstAppearance, overlayOpen,
  // black, ringing, sleepWindow, dismissed.

  @Test
  public void showsWhenIdleAndNothingBlocks() {
    assertTrue(CountdownGate.shouldShow(true, false, false, false, false, false, false, false));
    assertTrue(CountdownGate.shouldShow(true, false, true, false, false, false, false, false));
  }

  @Test
  public void noItemNeverShows() {
    assertFalse(CountdownGate.shouldShow(false, false, true, false, false, false, false, false));
    assertFalse(CountdownGate.shouldShow(false, true, true, false, false, false, false, false));
  }

  @Test
  public void firstAppearanceIsExemptFromTheTurnGate() {
    // The turn that created the timer must not hide the new screen behind
    // its own confirmation reply.
    assertTrue(CountdownGate.shouldShow(true, true, true, false, false, false, false, false));
  }

  @Test
  public void laterTurnsHideAnAlreadySeenItemUntilTheyEnd() {
    assertFalse(CountdownGate.shouldShow(true, true, false, false, false, false, false, false));
  }

  @Test
  public void hardBlocksWinEvenOnFirstAppearance() {
    assertFalse(CountdownGate.shouldShow(true, true, true, true, false, false, false, false));
    assertFalse(CountdownGate.shouldShow(true, true, true, false, true, false, false, false));
    assertFalse(CountdownGate.shouldShow(true, true, true, false, false, true, false, false));
    assertFalse(CountdownGate.shouldShow(true, true, true, false, false, false, true, false));
    assertFalse(CountdownGate.shouldShow(true, false, true, true, false, false, false, false));
    assertFalse(CountdownGate.shouldShow(true, false, false, false, false, false, true, false));
  }

  @Test
  public void dismissedItemStaysHidden() {
    assertFalse(CountdownGate.shouldShow(true, false, false, false, false, false, false, true));
    assertFalse(CountdownGate.shouldShow(true, true, true, false, false, false, false, true));
  }
}

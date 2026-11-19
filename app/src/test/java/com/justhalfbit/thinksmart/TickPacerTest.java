package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;

import org.junit.Test;

public class TickPacerTest {

  @Test
  public void firstDelayIsOnePeriod() {
    TickPacer p = new TickPacer();
    assertEquals(1000, p.nextDelayMs(5000));
  }

  @Test
  public void lateTicksShortenTheNextDelayInsteadOfDrifting() {
    // Regression: re-arming with a fixed 1000 ms after each run made
    // every period 1000 ms + work + slop, so the phase drifted until a
    // countdown value was skipped. Here every tick is posted 150 ms
    // after its planned instant, and the delays absorb that: the planned
    // instants stay on the grid (10000, 11000, 12000, ...).
    TickPacer p = new TickPacer();
    assertEquals(1000, p.nextDelayMs(9000)); // anchors: next planned 10000
    assertEquals(850, p.nextDelayMs(10150));
    assertEquals(850, p.nextDelayMs(11150));
    assertEquals(850, p.nextDelayMs(12150));
  }

  @Test
  public void moderateLagKeepsThePhaseWithOneShortDelay() {
    TickPacer p = new TickPacer();
    assertEquals(1000, p.nextDelayMs(0)); // planned 1000
    // The 1000 tick is only posted at 1900 (a 900 ms stall): the next
    // planned instant is still 2000, so the delay nearly vanishes rather
    // than pushing the whole schedule out by the stall.
    assertEquals(100, p.nextDelayMs(1900));
    assertEquals(1000, p.nextDelayMs(2000)); // planned 3000, back on phase
  }

  @Test
  public void lagOverOnePeriodReanchorsInsteadOfBursting() {
    TickPacer p = new TickPacer();
    assertEquals(1000, p.nextDelayMs(0)); // planned 1000
    // A 2.5 s stall: re-anchor at the stall's end, no catch-up burst.
    assertEquals(1000, p.nextDelayMs(3500)); // planned 4500
    assertEquals(1000, p.nextDelayMs(4500)); // planned 5500
    assertEquals(850, p.nextDelayMs(5650)); // and the new phase holds
  }

  @Test
  public void resetReanchorsThePhase() {
    TickPacer p = new TickPacer();
    assertEquals(1000, p.nextDelayMs(0));
    p.reset();
    assertEquals(1000, p.nextDelayMs(123456));
    assertEquals(850, p.nextDelayMs(124606));
  }

  @Test
  public void flipDelayLandsJustAfterTheNextFlip() {
    // ends_at - now = 9500: the display shows 10 and flips to 9 when the
    // difference crosses 9000 — 500 ms from now, on ends_at's own
    // millisecond phase rather than a wall-clock second boundary.
    assertEquals(550, TickPacer.flipDelayMs(1_000_000, 1_009_500));
    // 1 ms before a flip.
    assertEquals(51, TickPacer.flipDelayMs(1_000_000, 1_009_001));
    // Just after a flip: nearly a full second to the next one.
    assertEquals(1049, TickPacer.flipDelayMs(1_000_000, 1_008_999));
  }

  @Test
  public void flipDelayOnAnExactFlipWaitsForTheNextOne() {
    // The difference is a whole number of seconds: the value has just
    // flipped, so the next flip is a full period away.
    assertEquals(1050, TickPacer.flipDelayMs(1_000_000, 1_009_000));
  }

  @Test
  public void flipDelayIsSteadyOnceAligned() {
    // Firing exactly at flip + offset each round: the difference is
    // always a whole second minus the offset, giving a 1000 ms cadence.
    assertEquals(1000, TickPacer.flipDelayMs(1_000_050, 1_010_000));
  }

  @Test
  public void flipDelayEndsWhenTheTimerHasEnded() {
    assertEquals(-1, TickPacer.flipDelayMs(1_000_000, 1_000_000));
    assertEquals(-1, TickPacer.flipDelayMs(1_000_000, 999_000));
    // Half a second left: the last flip is the end itself.
    assertEquals(550, TickPacer.flipDelayMs(1_000_000, 1_000_500));
  }
}

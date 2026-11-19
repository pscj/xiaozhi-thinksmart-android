package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;

import org.junit.Test;

public class CountdownExemptionTest {

  @Test
  public void creatingTurnStaysExemptForEveryTick() {
    // Regression: the exemption must last the whole creating turn, not a
    // single tick — otherwise the screen flashes up on the first tick and
    // hides until the turn ends (a 60 s timer first seen at 47 s).
    CountdownExemption e = new CountdownExemption();
    assertTrue(e.exempt("t1", true)); // thinking
    assertTrue(e.exempt("t1", true)); // replying
    assertTrue(e.exempt("t1", true)); // still replying
  }

  @Test
  public void exemptionIsSpentOnceTheCreatingTurnIsObservedOver() {
    CountdownExemption e = new CountdownExemption();
    assertTrue(e.exempt("t1", true));
    assertFalse(e.exempt("t1", false)); // turn over: spent on this tick
    assertFalse(e.exempt("t1", false));
  }

  @Test
  public void laterTurnsAreNotExempt() {
    CountdownExemption e = new CountdownExemption();
    assertTrue(e.exempt("t1", true));
    assertFalse(e.exempt("t1", false)); // creating turn ends, exemption spent
    assertFalse(e.exempt("t1", true)); // a later turn gates the item normally
    assertFalse(e.exempt("t1", false));
  }

  @Test
  public void itemAppearingWithNoTurnInFlightHoldsNoExemption() {
    CountdownExemption e = new CountdownExemption();
    assertFalse(e.exempt("t1", false)); // appears while idle: needs none
    assertFalse(e.exempt("t1", true)); // so the next turn gates it at once
    assertFalse(e.exempt("t1", false));
  }

  @Test
  public void newItemIdStartsOver() {
    CountdownExemption e = new CountdownExemption();
    assertTrue(e.exempt("t1", true));
    assertFalse(e.exempt("t1", false)); // t1's exemption spent
    assertTrue(e.exempt("t2", true)); // t2 first appears mid-turn: exempt
    assertTrue(e.exempt("t2", true));
    assertFalse(e.exempt("t2", false));
    assertFalse(e.exempt("t2", true)); // and spent for t2 as well
  }

  @Test
  public void noItemClearsTheTrackedEntry() {
    CountdownExemption e = new CountdownExemption();
    assertTrue(e.exempt("t1", true));
    assertFalse(e.exempt("", true)); // display entry gone mid-turn
    // The same id afterwards counts as a fresh first appearance.
    assertTrue(e.exempt("t1", true));
    assertFalse(e.exempt("t1", false));
  }

  @Test
  public void gateSequenceKeepsTheScreenUpForTheWholeCreatingTurn() {
    // End-to-end over CountdownGate: hasItem true, no hard blocks, not
    // dismissed — only the turn gate and the exemption move.
    CountdownExemption e = new CountdownExemption();
    assertTrue(show(e, "t1", true)); // creating turn: thinking
    assertTrue(show(e, "t1", true)); // creating turn: replying
    assertTrue(show(e, "t1", true)); // creating turn: still replying
    assertTrue(show(e, "t1", false)); // turn over: up on the plain gate
    assertFalse(show(e, "t1", true)); // a later turn hides it
    assertTrue(show(e, "t1", false)); // and it returns when that turn ends
  }

  private static boolean show(CountdownExemption e, String id, boolean turnInFlight) {
    return CountdownGate.shouldShow(
        true, turnInFlight, e.exempt(id, turnInFlight), false, false, false, false, false);
  }
}

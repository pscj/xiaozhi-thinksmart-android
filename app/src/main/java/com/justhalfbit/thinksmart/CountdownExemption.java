package com.justhalfbit.thinksmart;

/**
 * Turn-gate exemption for the countdown screen's display item. Pure logic,
 * mirroring KeywordHit; CountdownGate stays a pure predicate and this class
 * feeds its firstAppearance input.
 *
 * <p>The exemption belongs to the turn that created the item, not to a
 * single tick. When an item first becomes the display entry while a turn
 * is in flight, the exemption is live for that item: every tick of the
 * same turn reports exempt, so the screen stays up through the creating
 * turn's thinking and reply instead of flashing for one tick and hiding
 * until the turn ends. The first tick that observes the turn over spends
 * the exemption; from then on the item is gated normally — later turns
 * hide the screen until they end. An item that first appears with no turn
 * in flight never holds an exemption, and a different item id starts
 * over.
 */
final class CountdownExemption {
  private String itemId = "";
  private boolean live;

  /**
   * Observe one upkeep tick for the current display item ("" when there is
   * none) and answer whether the turn gate is exempt for it on this tick.
   */
  boolean exempt(String currentItemId, boolean turnInFlight) {
    if (currentItemId == null || currentItemId.isEmpty()) {
      itemId = "";
      live = false;
      return false;
    }
    if (!currentItemId.equals(itemId)) {
      // A new display entry: a first appearance mid-turn is exempt for the
      // rest of that turn; with no turn in flight it needs no exemption.
      itemId = currentItemId;
      live = turnInFlight;
      return live;
    }
    if (!live) return false;
    if (!turnInFlight) {
      // The creating turn has been observed over: the exemption is spent.
      live = false;
      return false;
    }
    return true;
  }
}

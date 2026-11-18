package com.justhalfbit.thinksmart;

import java.util.Set;

/** Classifies local keyword-spotter hits: wake phrase vs. stop word. Pure logic. */
final class KeywordHit {
  enum Kind {
    NONE,
    WAKE,
    STOP
  }

  static final String STOP_HALT = "停止";
  static final String STOP_PAUSE = "暂停";

  private KeywordHit() {}

  static boolean isStopWord(String keyword) {
    return STOP_HALT.equals(keyword) || STOP_PAUSE.equals(keyword);
  }

  /**
   * Stop words win in every mode, ahead of the wake phrase. With a custom phrase
   * configured, only that phrase wakes; in default mode any bundled preset phrase
   * wakes. Anything else is swallowed, as before.
   */
  static Kind classify(String hit, String customPhrase, Set<String> defaultPhrases) {
    if (hit == null || hit.isEmpty()) return Kind.NONE;
    if (isStopWord(hit)) return Kind.STOP;
    if (customPhrase != null && !customPhrase.isEmpty())
      return customPhrase.equals(hit) ? Kind.WAKE : Kind.NONE;
    return defaultPhrases != null && defaultPhrases.contains(hit) ? Kind.WAKE : Kind.NONE;
  }

  /**
   * Self-trigger guard: true when the sentence currently being played itself
   * mentions a stop word, so a spotter hit is the device hearing its own reply.
   */
  static boolean sentenceMentionsStopWord(String sentence) {
    return sentence != null && (sentence.contains(STOP_HALT) || sentence.contains(STOP_PAUSE));
  }

  /** What a spotter hit should do about a ringing alarm, decided ahead of any turn logic. */
  enum RingAction {
    NONE,
    STOP_RING,
    STOP_RING_AND_WAKE
  }

  /**
   * While an alarm rings, any hit silences it first: a stop word goes no
   * further, a wake hit stops the ring and then wakes as usual. When nothing
   * rings (or the hit is not a keyword at all), turn logic is left untouched.
   */
  static RingAction ringAction(Kind kind, boolean ringing) {
    if (!ringing || kind == null || kind == Kind.NONE) return RingAction.NONE;
    return kind == Kind.STOP ? RingAction.STOP_RING : RingAction.STOP_RING_AND_WAKE;
  }
}

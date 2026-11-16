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
}

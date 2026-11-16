package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class KeywordHitTest {
  private static Set<String> defaults() {
    Set<String> set = new HashSet<>();
    set.add("小麦小麦");
    set.add("哈喽小麦");
    set.add("小麦同学");
    return set;
  }

  @Test public void stopWordsWinInEveryMode() {
    assertEquals(KeywordHit.Kind.STOP, KeywordHit.classify("停止", "", defaults()));
    assertEquals(KeywordHit.Kind.STOP, KeywordHit.classify("暂停", "", defaults()));
    assertEquals(KeywordHit.Kind.STOP, KeywordHit.classify("停止", "小程同学", defaults()));
    assertEquals(KeywordHit.Kind.STOP, KeywordHit.classify("暂停", "小程同学", defaults()));
  }

  @Test public void customModeOnlyConfiguredPhraseWakes() {
    assertEquals(KeywordHit.Kind.WAKE, KeywordHit.classify("小程同学", "小程同学", defaults()));
    assertEquals(KeywordHit.Kind.NONE, KeywordHit.classify("小麦小麦", "小程同学", defaults()));
    assertEquals(KeywordHit.Kind.NONE, KeywordHit.classify("哈喽小麦", "小程同学", defaults()));
  }

  @Test public void defaultModeAnyPresetWakes() {
    assertEquals(KeywordHit.Kind.WAKE, KeywordHit.classify("小麦小麦", "", defaults()));
    assertEquals(KeywordHit.Kind.WAKE, KeywordHit.classify("哈喽小麦", "", defaults()));
    assertEquals(KeywordHit.Kind.WAKE, KeywordHit.classify("小麦同学", "", defaults()));
    assertEquals(KeywordHit.Kind.NONE, KeywordHit.classify("小程同学", "", defaults()));
  }

  @Test public void emptyHitIsNone() {
    assertEquals(KeywordHit.Kind.NONE, KeywordHit.classify("", "", defaults()));
    assertEquals(KeywordHit.Kind.NONE, KeywordHit.classify(null, "", defaults()));
    assertEquals(KeywordHit.Kind.NONE, KeywordHit.classify("小麦小麦", "", null));
  }

  @Test public void sentenceGuardDetectsStopWords() {
    assertTrue(KeywordHit.sentenceMentionsStopWord("好的，已经停止了"));
    assertTrue(KeywordHit.sentenceMentionsStopWord("先暂停一下，我马上回来"));
    assertFalse(KeywordHit.sentenceMentionsStopWord("今天北京晴，最高十三度"));
    assertFalse(KeywordHit.sentenceMentionsStopWord(""));
    assertFalse(KeywordHit.sentenceMentionsStopWord(null));
  }
}

package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class WakeMatcherTest {
  @Test
  public void matchesHomophonesAndRejectsConfusableNames() throws Exception {
    WakeMatcher matcher = new WakeMatcher(new FileInputStream("src/main/assets/kws-zh/pinyin.txt"));
    for (String phrase : new String[] {"小麦小麦", "小麦", "哈喽小麦", "Hello 小麦", "小麦同学", "小卖小卖", "笑麦", ""})
      assertTrue(phrase, matcher.matches(phrase));
    for (String phrase : new String[] {"小妹小妹", "小美同学", "小白同学", "今天晚上要下雨了"})
      assertFalse(phrase, matcher.matches(phrase));
  }
  @Test
  public void checksTheConfiguredPhrase() throws Exception {
    WakeMatcher matcher = new WakeMatcher(new FileInputStream("src/main/assets/kws-zh/pinyin.txt"));
    assertTrue(matcher.matches("你好小智", "你好小智"));
    assertFalse(matcher.matches("今天晚上要下雨了", "你好小智"));
  }
}

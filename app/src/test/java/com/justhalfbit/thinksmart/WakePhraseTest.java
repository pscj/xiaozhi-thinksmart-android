package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;
import java.io.FileInputStream;
import org.junit.Test;

public class WakePhraseTest {
  private String convert(String phrase) throws Exception {
    return WakePhrase.keywords(new FileInputStream("src/main/assets/kws-zh/tokens.txt"),
        new FileInputStream("src/main/assets/kws-zh/han-pinyin-tone.txt"), phrase);
  }

  @Test public void convertsAndValidatesCustomPhrase() throws Exception {
    assertEquals("x iǎo m ài x iǎo m ài @小麦小麦", convert("小麦小麦"));
    assertEquals("n ǐ h ǎo x iǎo zh ì @你好小智", convert("你好小智"));
    try { convert("hello"); fail("应拒绝非汉字"); }
    catch (IllegalArgumentException expected) { }
  }
}

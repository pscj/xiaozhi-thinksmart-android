package com.justhalfbit.thinksmart;

import android.content.res.AssetManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

/** Converts a Chinese wake phrase to the model's initial/final tokens. */
final class WakePhrase {
  static final String KEY = "customWakePhrase";

  static String keywords(AssetManager assets, String phrase) throws Exception {
    try (InputStream tokens = assets.open("kws-zh/tokens.txt");
        InputStream pinyin = assets.open("kws-zh/han-pinyin-tone.txt")) {
      return keywords(tokens, pinyin, phrase);
    }
  }

  static String keywords(InputStream tokenFile, InputStream pinyinFile, String phrase) throws Exception {
    phrase = phrase.trim();
    if (phrase.length() < 2 || phrase.length() > 12 || !phrase.matches("[\\u4e00-\\u9fff]+"))
      throw new IllegalArgumentException("请输入 2 至 12 个汉字");
    Set<String> tokens = new HashSet<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        tokenFile, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) tokens.add(line.split("\\s+")[0]);
    }
    Map<Character, String> pinyin = new HashMap<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        pinyinFile, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(" ");
        if (parts.length == 2) pinyin.put(parts[0].charAt(0), parts[1]);
      }
    }
    StringBuilder result = new StringBuilder();
    for (char character : phrase.toCharArray()) {
      String syllable = pinyin.get(character);
      if (syllable == null) throw new IllegalArgumentException("无法识别“" + character + "”的拼音，请换一个唤醒词");
      String initial = "";
      for (String candidate : new String[] {"zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w"}) {
        if (syllable.startsWith(candidate)) { initial = candidate; break; }
      }
      String fin = syllable.substring(initial.length());
      if ((!initial.isEmpty() && !tokens.contains(initial)) || !tokens.contains(fin))
        throw new IllegalArgumentException("无法识别该词的拼音，请换一个唤醒词");
      if (!initial.isEmpty()) result.append(initial).append(' ');
      result.append(fin).append(' ');
    }
    return result.append('@').append(phrase).toString();
  }
}

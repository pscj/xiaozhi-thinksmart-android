package com.justhalfbit.thinksmart;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Baseline wake_verify.py's toneless-pinyin rules, ported from the MIT upstream. */
final class WakeMatcher {
  private final Map<Character, String> table = new HashMap<>();
  private static final String[] LABELS = {"小麦小麦", "哈喽小麦", "小麦同学"};

  WakeMatcher(InputStream input) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 2 && !parts[0].startsWith("#"))
          for (char c : parts[1].toCharArray()) table.put(c, parts[0]);
      }
    }
  }

  private List<String> pinyin(String text) {
    text = text.toLowerCase(Locale.ROOT).replaceAll("hello|hallo|halo", "哈喽");
    List<String> out = new ArrayList<>();
    for (char c : text.toCharArray())
      if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) out.add(table.get(c));
    return out;
  }

  private String initial(String syllable) {
    if (syllable == null) return "?";
    for (String prefix :
        new String[] {
          "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
          "r", "z", "c", "s", "y", "w"
        }) if (syllable.startsWith(prefix)) return prefix;
    return "";
  }

  private double distance(String a, String b) {
    if (a == null || b == null) return 1;
    if (a.equals(b)) return 0;
    String ia = initial(a), ib = initial(b);
    return ia.equals(ib) || a.substring(ia.length()).equals(b.substring(ib.length())) ? .5 : 1;
  }

  private double cost(List<String> heard, String label, int wrongName) {
    List<String> wanted = pinyin(label);
    double best = 99;
    for (int start = 0; start <= heard.size() - wanted.size(); start++) {
      double total = 0;
      for (int k = 0; k < wanted.size(); k++) {
        double d = distance(heard.get(start + k), wanted.get(k));
        total += label.charAt(k) == '麦' && d != 0 ? wrongName : d;
      }
      best = Math.min(best, total);
    }
    return best;
  }

  boolean matches(String text, String customPhrase) {
    if (!customPhrase.isEmpty()) {
      List<String> heard = pinyin(text);
      return cost(heard, customPhrase, 1) <= 1 || heard.size() <= 3;
    }
    return matches(text);
  }

  boolean matches(String text) {
    List<String> heard = pinyin(text);
    for (String label : LABELS) if (cost(heard, label, 99) <= 1) return true;
    if (cost(heard, "小麦", 99) <= .5) return true;
    for (String label : LABELS) if (cost(heard, label, 1) <= 2) return false;
    return heard.size() <= 3;
  }
}

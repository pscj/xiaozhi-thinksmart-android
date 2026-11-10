package com.justhalfbit.thinksmart;

/** Pure protocol invariants shared by the audio engine and tests. */
public final class Protocol {
  public static final int INPUT_RATE = 16000, FRAME_SAMPLES = 320;

  public static boolean validOutputRate(int rate) {
    return rate == 8000 || rate == 12000 || rate == 16000 || rate == 24000 || rate == 48000;
  }

  public static int percent(int value) {
    return Math.max(0, Math.min(100, value));
  }

  public static boolean inSleep(int now, int start, int end) {
    return start == end || (start < end ? now >= start && now < end : now >= start || now < end);
  }

  public static int minutes(String time) {
    if (!time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
      throw new IllegalArgumentException("时间格式应为 HH:MM");
    return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3));
  }

  public static boolean isWakeText(String text) {
    String normalized = text.replaceAll("[\\p{P}\\s]", "");
    return normalized.contains("小麦小麦")
        || normalized.contains("哈喽小麦")
        || normalized.contains("小麦同学");
  }

  public static boolean idleExpired(long now, long last, int minutes, boolean active) {
    return !active && minutes > 0 && now - last >= minutes * 60000L;
  }

  public static boolean conversationActive(String state) {
    return "聆听中".equals(state)
        || "识别中".equals(state)
        || "思考中".equals(state)
        || "回复中".equals(state);
  }

  private Protocol() {}
}

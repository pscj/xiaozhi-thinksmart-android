package com.justhalfbit.thinksmart;

/** Recording-thread owned, three seconds of signed little-endian PCM. */
final class PcmRing {
  private final short[] samples;
  private int cursor, size;

  PcmRing() { this(48000); }

  PcmRing(int capacity) { samples = new short[capacity]; }

  synchronized void add(short[] frame) {
    for (short value : frame) {
      samples[cursor] = value;
      cursor = (cursor + 1) % samples.length;
    }
    size = Math.min(samples.length, size + frame.length);
  }

  synchronized byte[] snapshot() {
    byte[] out = new byte[size * 2];
    for (int i = 0; i < size; i++) {
      short value = samples[(cursor - size + samples.length + i) % samples.length];
      out[2 * i] = (byte) value;
      out[2 * i + 1] = (byte) (value >> 8);
    }
    return out;
  }
}

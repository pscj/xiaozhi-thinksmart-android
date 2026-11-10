package com.justhalfbit.thinksmart;

/** Bounded adaptive gain; the captured PCM is never modified. */
final class WakeGain {
  private float gain = 3.1623f;

  float[] process(short[] pcm, double rms) {
    int peak = 0;
    for (short sample : pcm) peak = Math.max(peak, Math.abs((int) sample));
    float peakFraction = peak / 32768f;
    if (rms >= 0.0001) {
      float target = (float) Math.max(3.1623, Math.min(32.0, 0.04 / rms));
      gain += (target - gain) * (target < gain ? 1f : 0.25f);
    }
    float safeGain = peakFraction == 0 ? gain : Math.min(gain, 0.95f / peakFraction);
    float[] samples = new float[pcm.length];
    for (int i = 0; i < pcm.length; i++) samples[i] = pcm[i] / 32768f * safeGain;
    return samples;
  }

  short[] processPcm16(short[] pcm, double rms) {
    float[] samples = process(pcm, rms);
    short[] output = new short[samples.length];
    for (int i = 0; i < samples.length; i++) output[i] = (short) Math.round(samples[i] * 32767f);
    return output;
  }
}

package com.justhalfbit.thinksmart;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Locally synthesized alarm chime: a three-note sine bell sequence baked into
 * one PCM buffer and looped through a static AudioTrack, following the
 * previewRecentAudio pattern in AudioEngine. No audio assets and no new
 * dependencies; plays on the media stream at its current volume (the system
 * volume is never touched). The buffer ends in silence so the loop point is
 * click-free.
 */
final class AlarmSound {
  private static final int RATE = 24000;
  private AudioTrack track;
  private byte[] pcm;

  synchronized void start() {
    if (track != null) return;
    if (pcm == null) pcm = synthesize();
    AudioTrack created = null;
    try {
      created =
          new AudioTrack(
              new AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_MEDIA)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                  .build(),
              new AudioFormat.Builder()
                  .setSampleRate(RATE)
                  .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                  .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                  .build(),
              pcm.length,
              AudioTrack.MODE_STATIC,
              AudioManager.AUDIO_SESSION_ID_GENERATE);
      // Static tracks report STATE_NO_STATIC_DATA until their first successful write.
      if (created.getState() == AudioTrack.STATE_UNINITIALIZED
          || created.write(pcm, 0, pcm.length) != pcm.length
          || created.getState() != AudioTrack.STATE_INITIALIZED)
        throw new IllegalStateException("闹钟铃声初始化失败");
      created.setLoopPoints(0, pcm.length / 2, -1);
      created.play();
      track = created;
    } catch (Exception e) {
      if (created != null) created.release();
    }
  }

  synchronized void stop() {
    AudioTrack current = track;
    track = null;
    if (current == null) return;
    try {
      current.pause();
      current.flush();
      current.stop();
    } catch (Exception ignored) {
    }
    current.release();
  }

  synchronized boolean playing() {
    return track != null;
  }

  /** E6–G6–C7 bell notes with a soft harmonic and a raised-cosine envelope. */
  private static byte[] synthesize() {
    double[] notes = {1318.51, 1567.98, 2093.00};
    int noteSamples = (int) (RATE * 0.16), gapSamples = (int) (RATE * 0.05);
    int tailSamples = (int) (RATE * 0.8);
    int total = notes.length * noteSamples + (notes.length - 1) * gapSamples + tailSamples;
    byte[] out = new byte[total * 2];
    int pos = 0;
    for (double freq : notes) {
      for (int i = 0; i < noteSamples; i++) {
        double t = i / (double) RATE;
        double envelope = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / noteSamples);
        double sample =
            Math.sin(2 * Math.PI * freq * t) + 0.3 * Math.sin(4 * Math.PI * freq * t);
        writeSample(out, pos++, 0.45 * envelope * sample / 1.3);
      }
      pos += gapSamples; // zero-filled silence between notes
    }
    return out;
  }

  private static void writeSample(byte[] out, int index, double value) {
    int v = (int) Math.round(Math.max(-1, Math.min(1, value)) * 32767);
    out[index * 2] = (byte) (v & 0xff);
    out[index * 2 + 1] = (byte) ((v >> 8) & 0xff);
  }
}

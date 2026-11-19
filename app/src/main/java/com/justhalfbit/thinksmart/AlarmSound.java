package com.justhalfbit.thinksmart;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Locally synthesized alarm sound: the classic digital-alarm beep — a 2 kHz
 * tone with light harmonics, four short beeps per group — baked into one PCM
 * buffer and looped through a static AudioTrack, following the
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
      Log.e("AlarmSound", "闹钟铃声启动失败", e);
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

  /**
   * One 1.2 s beep group: four 0.1 s beeps of 2 kHz (fundamental plus light
   * harmonics) spaced 0.1 s apart, then 0.4 s of silence before the group
   * loops. Short raised-cosine edges keep every beep click-free.
   */
  private static byte[] synthesize() {
    int beepSamples = (int) (RATE * 0.1), gapSamples = (int) (RATE * 0.1);
    int tailSamples = (int) (RATE * 0.4);
    int total = 4 * (beepSamples + gapSamples) + tailSamples;
    byte[] out = new byte[total * 2];
    int edge = (int) (RATE * 0.004);
    int pos = 0;
    for (int beep = 0; beep < 4; beep++) {
      for (int i = 0; i < beepSamples; i++) {
        double t = i / (double) RATE;
        double envelope = 1;
        if (i < edge) {
          envelope = 0.5 - 0.5 * Math.cos(Math.PI * i / edge);
        } else if (i >= beepSamples - edge) {
          int j = i - (beepSamples - edge);
          envelope = 0.5 + 0.5 * Math.cos(Math.PI * j / edge);
        }
        double sample =
            Math.sin(2 * Math.PI * 2000 * t)
                + 0.25 * Math.sin(4 * Math.PI * 2000 * t)
                + 0.1 * Math.sin(6 * Math.PI * 2000 * t);
        writeSample(out, pos++, 0.62 * envelope * sample / 1.35);
      }
      pos += gapSamples; // zero-filled silence between beeps
    }
    return out;
  }

  private static void writeSample(byte[] out, int index, double value) {
    int v = (int) Math.round(Math.max(-1, Math.min(1, value)) * 32767);
    out[index * 2] = (byte) (v & 0xff);
    out[index * 2 + 1] = (byte) ((v >> 8) & 0xff);
  }
}

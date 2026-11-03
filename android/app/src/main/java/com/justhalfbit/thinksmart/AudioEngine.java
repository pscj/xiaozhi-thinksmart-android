package com.justhalfbit.thinksmart;

import android.content.Context;
import android.media.*;
import android.media.audiofx.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.concentus.*;

final class AudioEngine {
  interface Callback {
    void frame(byte[] frame, boolean speech);

    void wake();

    void error(String text);
  }

  private final Context context;
  private final Callback callback;
  private volatile boolean running, wakeEnabled = true;
  private Thread recorderThread, playerThread;
  private volatile AudioRecord recorder;
  private volatile AudioTrack track;
  private final Object trackLock = new Object();
  private final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(400);
  private final AtomicInteger generation = new AtomicInteger();
  private volatile int outputRate = 24000;
  private volatile long written, turnBase;
  private AcousticEchoCanceler aec;
  private NoiseSuppressor ns;

  private static final class Packet {
    final byte[] bytes;
    final int generation;
    final Runnable drained;

    Packet(byte[] b, int g, Runnable r) {
      bytes = b;
      generation = g;
      drained = r;
    }
  }

  AudioEngine(Context context, Callback cb) {
    this.context = context;
    callback = cb;
  }

  void setWakeEnabled(boolean value) {
    wakeEnabled = value;
  }

  void start() {
    running = true;
    recorderThread = new Thread(this::recordLoop, "microphone");
    recorderThread.start();
    playerThread = new Thread(this::playLoop, "opus-player");
    playerThread.start();
  }

  private void recordLoop() {
    WakeWord kws = null;
    try {
      try {
        kws = new WakeWord(context.getAssets());
      } catch (Throwable e) {
        callback.error("本地唤醒加载失败，可使用按钮：" + e.getClass().getSimpleName());
      }
      OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
      encoder.setBitrate(24000);
      encoder.setComplexity(3);
      if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
          != android.content.pm.PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("麦克风权限未授权");
      int min =
          AudioRecord.getMinBufferSize(
              16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      recorder =
          new AudioRecord(
              MediaRecorder.AudioSource.VOICE_COMMUNICATION,
              16000,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              Math.max(min, 6400));
      if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
        throw new IllegalStateException("录音设备初始化失败");
      int session = recorder.getAudioSessionId();
      if (AcousticEchoCanceler.isAvailable()) {
        aec = AcousticEchoCanceler.create(session);
        if (aec != null) aec.setEnabled(true);
      }
      if (NoiseSuppressor.isAvailable()) {
        ns = NoiseSuppressor.create(session);
        if (ns != null) ns.setEnabled(true);
      }
      recorder.startRecording();
      short[] pcm = new short[320];
      byte[] packet = new byte[4000];
      int pos = 0;
      while (running) {
        int n = recorder.read(pcm, pos, pcm.length - pos);
        if (n < 0) throw new IllegalStateException("麦克风读取错误 " + n);
        if (n == 0) continue;
        pos += n;
        if (pos < pcm.length) continue;
        pos = 0;
        if (kws != null && wakeEnabled && kws.feed(pcm)) callback.wake();
        int count = encoder.encode(pcm, 0, 320, packet, 0, packet.length);
        double energy = 0;
        for (short v : pcm) energy += (double) v * v;
        callback.frame(Arrays.copyOf(packet, count), Math.sqrt(energy / 320) / 32768.0 > 0.0056);
      }
    } catch (Exception e) {
      if (running) callback.error("录音停止：" + e.getMessage());
    } finally {
      if (kws != null) kws.close();
      if (aec != null) aec.release();
      if (ns != null) ns.release();
      AudioRecord r = recorder;
      recorder = null;
      if (r != null) {
        try {
          r.stop();
        } catch (Exception ignored) {
        }
        r.release();
      }
    }
  }

  void configure(int rate) {
    if (!Protocol.validOutputRate(rate)) throw new IllegalArgumentException("不支持音频采样率 " + rate);
    outputRate = rate;
    clear();
  }

  void play(byte[] bytes) {
    if (!queue.offer(new Packet(bytes, generation.get(), null)))
      callback.error("播放队列溢出，请检查网络或设备性能");
  }

  void drained(Runnable action) {
    if (!queue.offer(new Packet(null, generation.get(), action))) callback.error("播放队列溢出");
  }

  void newTurn() {
    clear();
  }

  long playedMs() {
    return Math.max(0, playbackPosition() - turnBase) * 1000 / outputRate;
  }

  private long playbackPosition() {
    synchronized (trackLock) {
      AudioTrack t = track;
      return t == null ? 0 : Integer.toUnsignedLong(t.getPlaybackHeadPosition());
    }
  }

  void clear() {
    synchronized (trackLock) {
      generation.incrementAndGet();
      queue.clear();
      AudioTrack t = track;
      if (t != null) {
        try {
          t.pause();
          t.flush();
          t.play();
        } catch (Exception ignored) {
        }
      }
      written = 0;
      turnBase = 0;
    }
  }

  private void playLoop() {
    OpusDecoder decoder = null;
    int rate = 0, gen = -1;
    try {
      while (running) {
        Packet p = queue.poll(200, TimeUnit.MILLISECONDS);
        if (p == null) continue;
        if (p.generation != generation.get()) continue;
        synchronized (trackLock) {
          if (rate != outputRate || gen != p.generation) {
            if (track != null) {
              track.release();
              track = null;
            }
            rate = outputRate;
            gen = p.generation;
            decoder = new OpusDecoder(rate, 1);
            written = 0;
            turnBase = 0;
            int min =
                AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track =
                new AudioTrack(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                    Math.max(min, rate / 5 * 2),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.play();
          }
        }
        if (p.drained != null) {
          long until = android.os.SystemClock.elapsedRealtime() + 5000;
          while (running
              && p.generation == generation.get()
              && playbackPosition() < written
              && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(10);
          if (running && p.generation == generation.get()) p.drained.run();
          continue;
        }
        short[] pcm = new short[rate * 120 / 1000];
        int n = decoder.decode(p.bytes, 0, p.bytes.length, pcm, 0, pcm.length, false);
        for (int offset = 0; offset < n && running && p.generation == generation.get(); ) {
          int count;
          synchronized (trackLock) {
            if (p.generation != generation.get()) break;
            count = track.write(pcm, offset, Math.min(320, n - offset), AudioTrack.WRITE_BLOCKING);
          }
          if (count <= 0) throw new IllegalStateException("播放错误 " + count);
          offset += count;
          written += count;
        }
      }
    } catch (Exception e) {
      if (running) callback.error("播放停止：" + e.getMessage());
    } finally {
      synchronized (trackLock) {
        AudioTrack t = track;
        track = null;
        if (t != null) t.release();
      }
    }
  }

  void stop() {
    running = false;
    generation.incrementAndGet();
    queue.clear();
    if (recorderThread != null) recorderThread.interrupt();
    if (playerThread != null) playerThread.interrupt();
  }
}

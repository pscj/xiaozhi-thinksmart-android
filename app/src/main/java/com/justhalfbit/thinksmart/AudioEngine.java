package com.justhalfbit.thinksmart;

import android.content.Context;
import android.media.*;
import android.media.audiofx.*;
import android.util.Log;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.concentus.*;

final class AudioEngine {
  interface Callback {
    void frame(byte[] frame, boolean speech);

    void wake(byte[] pcm);

    void error(String text);
  }

  private final Context context;
  private final Callback callback;
  private volatile boolean running, wakeEnabled = true;
  private volatile float inputLevel, outputLevel;
  private final PcmRing recentAudio = new PcmRing(160000);
  private volatile boolean previewing;
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
    while (running && recordSession()) { /* Reopen the input after a settings change. */ }
  }

  private boolean recordSession() {
    WakeWord kws = null;
    boolean restart = false;
    try {
      android.content.SharedPreferences settings = context.getSharedPreferences("config", Context.MODE_PRIVATE);
      boolean recognition = settings.getBoolean("recognitionInput", false);
      boolean suppressNoise = settings.getBoolean("inputNoiseSuppression", true);
      try {
        kws = new WakeWord(context.getAssets());
      } catch (Throwable e) {
        callback.error("本地唤醒加载失败，可使用按钮：" + e.getClass().getSimpleName());
      }
      String currentPhrase = null;
      OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
      encoder.setBitrate(24000);
      encoder.setComplexity(3);
      WakeGain conversationGain = new WakeGain();
      if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
          != android.content.pm.PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("麦克风权限未授权");
      int min =
          AudioRecord.getMinBufferSize(
              16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      recorder =
          new AudioRecord(
              recognition ? MediaRecorder.AudioSource.VOICE_RECOGNITION
                  : MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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
        if (ns != null) ns.setEnabled(suppressNoise);
      }
      recorder.startRecording();
      short[] pcm = new short[320];
      PcmRing ring = new PcmRing();
      byte[] packet = new byte[4000];
      int pos = 0;
      long traceAt = android.os.SystemClock.elapsedRealtime();
      int traceFrames = 0;
      double traceRawPeak = 0, traceUploadPeak = 0;
      while (running) {
        if (recognition != settings.getBoolean("recognitionInput", false)
            || suppressNoise != settings.getBoolean("inputNoiseSuppression", true)) {
          restart = true;
          break;
        }
        String requestedPhrase = settings.getString(WakePhrase.KEY, "");
        if (kws != null && !requestedPhrase.equals(currentPhrase)) {
          try {
            kws.setPhrase(requestedPhrase, requestedPhrase.isEmpty() ? ""
                : WakePhrase.keywords(context.getAssets(), requestedPhrase));
            currentPhrase = requestedPhrase;
          } catch (Exception e) {
            currentPhrase = requestedPhrase;
            callback.error("唤醒词加载失败：" + e.getMessage());
          }
        }
        int n = recorder.read(pcm, pos, pcm.length - pos);
        if (n < 0) throw new IllegalStateException("麦克风读取错误 " + n);
        if (n == 0) continue;
        pos += n;
        if (pos < pcm.length) continue;
        pos = 0;
        if (previewing) continue;
        ring.add(pcm);
        recentAudio.add(pcm);
        if (kws != null && wakeEnabled && kws.feed(pcm)) {
          callback.wake(ring.snapshot());
        }
        double energy = 0;
        for (short v : pcm) energy += (double) v * v;
        double rms = Math.sqrt(energy / 320) / 32768.0;
        inputLevel = (float) rms;
        short[] uploadPcm = conversationGain.processPcm16(pcm, rms);
        int count = encoder.encode(uploadPcm, 0, 320, packet, 0, packet.length);
        double uploadEnergy = 0;
        for (short v : uploadPcm) uploadEnergy += (double) v * v;
        double uploadRms = Math.sqrt(uploadEnergy / uploadPcm.length) / 32768.0;
        if (Log.isLoggable("VoiceTrace", Log.DEBUG)) {
          traceFrames++;
          traceRawPeak = Math.max(traceRawPeak, rms);
          traceUploadPeak = Math.max(traceUploadPeak, uploadRms);
          long now = android.os.SystemClock.elapsedRealtime();
          if (now - traceAt >= 1000) {
            Log.d("VoiceTrace", "capture elapsedMs=" + (now - traceAt) + " frames=" + traceFrames
                + " maxRawRms=" + traceRawPeak + " maxUploadRms=" + traceUploadPeak
                + " recognitionInput=" + recognition + " noiseSuppression=" + suppressNoise);
            traceAt = now;
            traceFrames = 0;
            traceRawPeak = traceUploadPeak = 0;
          }
        }
        callback.frame(Arrays.copyOf(packet, count), uploadRms > 0.0056);
      }
    } catch (Exception e) {
      if (running) callback.error("录音停止：" + e.getMessage());
    } finally {
      if (kws != null) kws.close();
      if (aec != null) { aec.release(); aec = null; }
      if (ns != null) { ns.release(); ns = null; }
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
    return restart;
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

  float level(boolean playing) {
    return playing ? outputLevel : inputLevel;
  }

  synchronized boolean previewRecentAudio() {
    if (!running || previewing) return false;
    byte[] pcm = recentAudio.snapshot();
    if (pcm.length == 0) return false;
    previewing = true;
    new Thread(() -> {
      AudioTrack preview = null;
      try {
        preview = new AudioTrack(
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            new AudioFormat.Builder().setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            pcm.length, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
        // Static tracks report STATE_NO_STATIC_DATA until their first successful write.
        if (preview.getState() == AudioTrack.STATE_UNINITIALIZED
            || preview.write(pcm, 0, pcm.length) != pcm.length
            || preview.getState() != AudioTrack.STATE_INITIALIZED)
          throw new IllegalStateException("试听设备初始化失败");
        preview.play();
        long until = android.os.SystemClock.elapsedRealtime() + pcm.length * 1000L / 32000 + 2000;
        while (running && preview.getPlaybackHeadPosition() < pcm.length / 2
            && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
      } catch (Exception e) {
        if (running) callback.error("试听失败：" + e.getMessage());
      } finally {
        if (preview != null) preview.release();
        previewing = false;
      }
    }, "recording-preview").start();
    return true;
  }

  void clear() {
    outputLevel = 0;
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
          if (p.generation != generation.get()) continue;
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
            if (track.getState() != AudioTrack.STATE_INITIALIZED)
              throw new IllegalStateException("扬声器初始化失败");
            track.play();
          }
        }
        if (p.drained != null) {
          // Streaming AudioTrack may wait for a full buffer before starting (or restarting
          // after an underrun). Pad the tail so even a one-packet reply becomes audible.
          long target = written;
          if (target > 0) {
            short[] silence = new short[track.getBufferSizeInFrames()];
            writePcm(silence, silence.length, p.generation);
          }
          long remaining = Math.max(0, target - playbackPosition());
          long until = android.os.SystemClock.elapsedRealtime() + remaining * 1000 / rate + 2000;
          while (running
              && p.generation == generation.get()
              && playbackPosition() < target
              && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(10);
          if (running && p.generation == generation.get()) p.drained.run();
          continue;
        }
        short[] pcm = new short[rate * 120 / 1000];
        try {
          int n = decoder.decode(p.bytes, 0, p.bytes.length, pcm, 0, pcm.length, false);
          double energy = 0;
          for (int i = 0; i < n; i++) energy += (double) pcm[i] * pcm[i];
          outputLevel = n == 0 ? 0 : (float) (Math.sqrt(energy / n) / 32768.0);
          writePcm(pcm, n, p.generation);
        } catch (OpusException | IllegalArgumentException e) {
          // A malformed server packet must not kill playback for all later replies.
          Log.e("AudioEngine", "Opus packet decode failed, bytes=" + p.bytes.length, e);
          callback.error("语音解码失败，已跳过异常音频包：" + e.getMessage());
          decoder = new OpusDecoder(rate, 1);
        }
      }
    } catch (Exception e) {
      if (running) {
        Log.e("AudioEngine", "Playback failed", e);
        callback.error("播放停止：" + e.getMessage());
      }
    } finally {
      synchronized (trackLock) {
        AudioTrack t = track;
        track = null;
        if (t != null) t.release();
      }
    }
  }

  private void writePcm(short[] pcm, int samples, int packetGeneration) {
    for (int offset = 0; offset < samples && running; ) {
      synchronized (trackLock) {
        if (packetGeneration != generation.get()) return;
        int count =
            track.write(pcm, offset, Math.min(320, samples - offset), AudioTrack.WRITE_BLOCKING);
        if (count <= 0) throw new IllegalStateException("播放错误 " + count);
        offset += count;
        written += count;
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

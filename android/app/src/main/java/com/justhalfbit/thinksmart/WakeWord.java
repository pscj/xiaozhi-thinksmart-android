package com.justhalfbit.thinksmart;

import android.content.res.AssetManager;
import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.*;

/** Owned exclusively by the recording thread, including release. */
final class WakeWord implements AutoCloseable {
  private final KeywordSpotter spotter;
  private final OnlineStream stream;
  private long lastHit;
  private int hang;
  private final java.util.ArrayDeque<float[]> preroll = new java.util.ArrayDeque<>();

  WakeWord(AssetManager assets) {
    OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
    transducer.setEncoder("kws-zh/encoder.onnx");
    transducer.setDecoder("kws-zh/decoder.onnx");
    transducer.setJoiner("kws-zh/joiner.onnx");
    OnlineModelConfig model = new OnlineModelConfig();
    model.setTransducer(transducer);
    model.setTokens("kws-zh/tokens.txt");
    model.setNumThreads(1);
    model.setProvider("cpu");
    model.setModelType("zipformer2");
    KeywordSpotterConfig cfg = new KeywordSpotterConfig();
    cfg.setModelConfig(model);
    cfg.setKeywordsFile("kws-zh/keywords.txt");
    cfg.setKeywordsScore(2.0f);
    cfg.setKeywordsThreshold(0.25f);
    cfg.setMaxActivePaths(8);
    cfg.setNumTrailingBlanks(1);
    spotter = new KeywordSpotter(assets, cfg);
    stream = spotter.createStream("");
  }

  boolean feed(short[] pcm) {
    float[] samples = new float[pcm.length];
    for (int i = 0; i < pcm.length; i++)
      samples[i] = Math.max(-1f, Math.min(1f, pcm[i] / 32768f * 3.1623f));
    double sum = 0;
    for (short sample : pcm) sum += (double) sample * sample;
    if (Math.sqrt(sum / pcm.length) / 32768.0 >= 0.0031623) {
      if (hang == 0) {
        for (float[] before : preroll) stream.acceptWaveform(before, 16000);
        preroll.clear();
      }
      hang = 50;
    } else if (hang == 0) {
      if (preroll.size() == 5) preroll.removeFirst();
      preroll.addLast(samples);
      return false;
    } else hang--;
    stream.acceptWaveform(samples, 16000);
    while (spotter.isReady(stream)) {
      spotter.decode(stream);
      if (!spotter.getResult(stream).getKeyword().isEmpty()) {
        spotter.reset(stream);
        long now = SystemClock.elapsedRealtime();
        if (now - lastHit > 2000) {
          lastHit = now;
          return true;
        }
      }
    }
    return false;
  }

  public void close() {
    stream.release();
    spotter.release();
  }
}

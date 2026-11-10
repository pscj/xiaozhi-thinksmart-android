package com.justhalfbit.thinksmart;

import android.content.res.AssetManager;
import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.*;

/** Owned exclusively by the recording thread, including release. */
final class WakeWord implements AutoCloseable {
  private final KeywordSpotter spotter;
  private OnlineStream stream;
  private String phrase = "";
  private long lastHit;
  private int hang;
  private final WakeGain gain = new WakeGain();
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

  void setPhrase(String phrase, String keywords) {
    OnlineStream replacement = spotter.createStream(keywords);
    stream.release();
    stream = replacement;
    this.phrase = phrase;
    hang = 0;
    preroll.clear();
  }

  boolean feed(short[] pcm) {
    double sum = 0;
    for (short sample : pcm) sum += (double) sample * sample;
    double rms = Math.sqrt(sum / pcm.length) / 32768.0;
    float[] samples = gain.process(pcm, rms);
    if (rms >= 0.0001) {
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
      String hit = spotter.getResult(stream).getKeyword();
      if (!hit.isEmpty()) {
        spotter.reset(stream);
        long now = SystemClock.elapsedRealtime();
        if ((phrase.isEmpty() || phrase.equals(hit)) && now - lastHit > 2000) {
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

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
  /** Keyword names bundled in kws-zh/keywords.txt; these wake in default mode. */
  private final java.util.Set<String> defaultPhrases = new java.util.HashSet<>();

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
    try (java.io.BufferedReader reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(
                assets.open("kws-zh/keywords.txt"), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        int at = line.lastIndexOf('@');
        if (at >= 0) {
          String name = line.substring(at + 1).trim();
          if (!name.isEmpty()) defaultPhrases.add(name);
        }
      }
    } catch (Exception ignored) {
      // Fall through to the bundled names below.
    }
    if (defaultPhrases.isEmpty()) {
      defaultPhrases.add("小麦小麦");
      defaultPhrases.add("哈喽小麦");
      defaultPhrases.add("小麦同学");
    }
  }

  void setPhrase(String phrase, String keywords) {
    OnlineStream replacement = spotter.createStream(keywords);
    stream.release();
    stream = replacement;
    this.phrase = phrase;
    hang = 0;
    preroll.clear();
  }

  KeywordHit.Kind feed(short[] pcm) {
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
      return KeywordHit.Kind.NONE;
    } else hang--;
    stream.acceptWaveform(samples, 16000);
    while (spotter.isReady(stream)) {
      spotter.decode(stream);
      String hit = spotter.getResult(stream).getKeyword();
      if (!hit.isEmpty()) {
        spotter.reset(stream);
        KeywordHit.Kind kind = KeywordHit.classify(hit, phrase, defaultPhrases);
        if (kind == KeywordHit.Kind.NONE) continue;
        long now = SystemClock.elapsedRealtime();
        if (now - lastHit > 2000) {
          lastHit = now;
          return kind;
        }
      }
    }
    return KeywordHit.Kind.NONE;
  }

  public void close() {
    stream.release();
    spotter.release();
  }
}

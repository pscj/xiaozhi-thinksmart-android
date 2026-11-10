package org.concentus;

import static org.junit.Assert.*;
import org.junit.Test;

public class CeltDeemphasisTest {
  @Test public void downsampledHybridPreservesSilkSpeechForEveryOutputRate() {
    for (int downsample : new int[] {1, 2, 3, 4, 6}) {
      short[] pcm = {77, 1000, -2000, 3000, -4000, 88};
      CeltCommon.deemphasis(new int[][] {new int[4 * downsample]}, new int[] {0},
          pcm, 1, 4 * downsample, 1, downsample, new int[] {0}, new int[] {0}, 1);
      assertArrayEquals("downsample=" + downsample,
          new short[] {77, 1000, -2000, 3000, -4000, 88}, pcm);
    }
  }

  @Test public void downsampledCeltReplacesPreviousOutputWhenNotAccumulating() {
    short[] pcm = {1000, -2000};
    CeltCommon.deemphasis(new int[][] {new int[6]}, new int[] {0},
        pcm, 0, 6, 1, 3, new int[] {0}, new int[] {0}, 0);
    assertArrayEquals(new short[] {0, 0}, pcm);
  }

  @Test public void downsampledHybridAddsCeltAndSaturatesInBothChannels() {
    short[] pcm = {30000, -30000, 1000, -1000};
    int signal = 10000 << CeltConstants.SIG_SHIFT;
    CeltCommon.deemphasis(new int[][] {{signal, 0, 0, signal, 0, 0},
        {-signal, 0, 0, -signal, 0, 0}}, new int[] {0, 0}, pcm, 0, 6, 2, 3,
        new int[] {0}, new int[] {0, 0}, 1);
    assertArrayEquals(new short[] {32767, -32768, 11000, -11000}, pcm);
  }
}

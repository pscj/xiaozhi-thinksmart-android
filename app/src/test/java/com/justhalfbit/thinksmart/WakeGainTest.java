package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;
import org.junit.Test;

public class WakeGainTest {
  @Test public void quietSpeechIsAmplifiedWithoutChangingInput() {
    WakeGain gain = new WakeGain();
    short[] pcm = new short[320];
    java.util.Arrays.fill(pcm, (short) 33);
    float[] out = null;
    for (int i = 0; i < 20; i++) out = gain.process(pcm, 33.0 / 32768);
    assertTrue(out[0] > 0.025f);
    assertTrue(out[0] <= 33f / 32768f * 32f);
    assertEquals(33, pcm[0]);
  }

  @Test public void loudSamplesStayBelowClipping() {
    WakeGain gain = new WakeGain();
    short[] pcm = {Short.MIN_VALUE, Short.MAX_VALUE};
    float[] out = gain.process(pcm, 0.99);
    assertTrue(Math.abs(out[0]) <= 0.95f);
    assertTrue(Math.abs(out[1]) <= 0.95f);
  }

  @Test public void uploadPcmBoostsQuietAudioWithoutMutatingOrClipping() {
    WakeGain gain = new WakeGain();
    short[] quiet = new short[320];
    java.util.Arrays.fill(quiet, (short) 33);
    short[] boosted = null;
    for (int i = 0; i < 20; i++) boosted = gain.processPcm16(quiet, 33.0 / 32768);
    assertTrue(boosted[0] > 819);
    assertEquals(33, quiet[0]);
    short[] loud = gain.processPcm16(new short[] {Short.MIN_VALUE, Short.MAX_VALUE}, 0.99);
    assertTrue(Math.abs((int) loud[0]) <= 31130);
    assertTrue(Math.abs((int) loud[1]) <= 31130);
  }
}

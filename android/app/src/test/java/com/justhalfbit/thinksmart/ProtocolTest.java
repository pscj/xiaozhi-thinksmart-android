package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;

import org.concentus.*;
import org.junit.Test;

public class ProtocolTest {
  @Test
  public void midnightSleepWindow() {
    assertTrue(Protocol.inSleep(23 * 60, 22 * 60, 7 * 60));
    assertTrue(Protocol.inSleep(0, 22 * 60, 7 * 60));
    assertFalse(Protocol.inSleep(7 * 60, 22 * 60, 7 * 60));
    assertFalse(Protocol.inSleep(12 * 60, 22 * 60, 7 * 60));
  }

  @Test
  public void sameDaySleepWindow() {
    assertTrue(Protocol.inSleep(12 * 60, 10 * 60, 14 * 60));
    assertFalse(Protocol.inSleep(14 * 60, 10 * 60, 14 * 60));
  }

  @Test
  public void timeAndPercent() {
    assertEquals(1439, Protocol.minutes("23:59"));
    assertEquals(0, Protocol.percent(-4));
    assertEquals(100, Protocol.percent(120));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsInvalidTime() {
    Protocol.minutes("24:00");
  }

  @Test
  public void supportsNegotiatedOpusRates() {
    for (int r : new int[] {8000, 12000, 16000, 24000, 48000})
      assertTrue(Protocol.validOutputRate(r));
    assertFalse(Protocol.validOutputRate(44100));
  }

  @Test
  public void realOpusRoundTripAndRateNegotiation() throws Exception {
    OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
    encoder.setBitrate(24000);
    encoder.setComplexity(3);
    OpusDecoder decoder = new OpusDecoder(24000, 1);
    short[] in = new short[320], out = new short[2880];
    byte[] packet = new byte[4000];
    double energy = 0;
    for (int frame = 0; frame < 50; frame++) {
      for (int i = 0; i < 320; i++)
        in[i] = (short) (12000 * Math.sin(2 * Math.PI * 440 * (frame * 320 + i) / 16000));
      int n = encoder.encode(in, 0, 320, packet, 0, packet.length);
      assertTrue(n > 0 && n < 4000);
      int count = decoder.decode(packet, 0, n, out, 0, out.length, false);
      assertEquals(480, count);
      if (frame > 10) for (int i = 0; i < count; i++) energy += out[i] * (double) out[i];
    }
    assertTrue("Decoded speech must have nonzero energy", energy > 1e10);
  }
}

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
  public void sleepWindowBoundaryPoints() {
    // Cross-midnight 22:00-07:00: the start point itself is inside, the end
    // point itself is already outside (the morning restore relies on this).
    assertTrue(Protocol.inSleep(22 * 60, 22 * 60, 7 * 60));
    assertFalse(Protocol.inSleep(21 * 60 + 59, 22 * 60, 7 * 60));
    assertTrue(Protocol.inSleep(6 * 60 + 59, 22 * 60, 7 * 60));
    assertFalse(Protocol.inSleep(7 * 60, 22 * 60, 7 * 60));
    assertFalse(Protocol.inSleep(7 * 60 + 1, 22 * 60, 7 * 60));
    // Same-day 10:00-14:00: same start-in / end-out convention.
    assertTrue(Protocol.inSleep(10 * 60, 10 * 60, 14 * 60));
    assertFalse(Protocol.inSleep(9 * 60 + 59, 10 * 60, 14 * 60));
    assertTrue(Protocol.inSleep(13 * 60 + 59, 10 * 60, 14 * 60));
    // A zero-length window (start == end) counts as all day.
    assertTrue(Protocol.inSleep(3 * 60, 8 * 60, 8 * 60));
    assertTrue(Protocol.inSleep(8 * 60, 8 * 60, 8 * 60));
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
  @Test
  public void decodesServerSizedFramesAtEveryOutputRate() throws Exception {
    // Server replies commonly use 60 ms packets, unlike the 20 ms microphone frames.
    for (int rate : new int[] {8000, 12000, 16000, 24000, 48000}) {
      OpusEncoder encoder = new OpusEncoder(rate, 1, OpusApplication.OPUS_APPLICATION_AUDIO);
      OpusDecoder decoder = new OpusDecoder(rate, 1);
      short[] input = new short[rate * 60 / 1000];
      short[] output = new short[rate * 120 / 1000];
      byte[] packet = new byte[4000];
      for (int frame = 0; frame < 8; frame++) {
        for (int i = 0; i < input.length; i++)
          input[i] = (short) (10000 * Math.sin(2 * Math.PI * 440 * i / rate));
        int bytes = encoder.encode(input, 0, input.length, packet, 0, packet.length);
        assertEquals(input.length, decoder.decode(packet, 0, bytes, output, 0, output.length, false));
      }
    }
  }

}

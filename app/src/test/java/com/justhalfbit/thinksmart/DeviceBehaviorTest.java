package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;

import org.junit.Test;

public class DeviceBehaviorTest {
  @Test
  public void ringWrapRetainsLatestThreeSecondsInLittleEndian() {
    PcmRing ring = new PcmRing();
    short[] frame = new short[320];
    for (int i = 0; i < 160; i++) {
      java.util.Arrays.fill(frame, (short) i);
      ring.add(frame);
    }
    byte[] pcm = ring.snapshot();
    assertEquals(96000, pcm.length);
    assertEquals(10, pcm[0]);
    assertEquals(0, pcm[1]);
    assertEquals(159, pcm[pcm.length - 2] & 255);
  }

  @Test
  public void screenTimerIgnoresActiveDialogueAndDisabledTimer() {
    assertFalse(Protocol.idleExpired(300000, 0, 5, true));
    assertFalse(Protocol.idleExpired(300000, 0, 0, false));
    assertFalse(Protocol.idleExpired(299999, 0, 5, false));
    assertTrue(Protocol.idleExpired(300000, 0, 5, false));
  }

  @Test
  public void screenTimerUsesEveryConfiguredDuration() {
    for (int minutes : new int[] {1, 3, 5, 10, 30}) {
      long started = 12345;
      long deadline = started + minutes * 60000L;
      assertFalse(Protocol.idleExpired(deadline - 1, started, minutes, false));
      assertTrue(Protocol.idleExpired(deadline, started, minutes, false));
      assertFalse(Protocol.idleExpired(deadline, started, minutes, true));
    }
  }

  @Test
  public void standbyDoesNotCountAsAnActiveConversation() {
    for (String state : new String[] {"聆听中", "识别中", "思考中", "回复中"})
      assertTrue(state, Protocol.conversationActive(state));
    for (String state : new String[] {"待机", "未连接", "连接中", "服务已停止"})
      assertFalse(state, Protocol.conversationActive(state));
  }

  @Test
  public void wakeConfirmationIgnoresPunctuationButRejectsConfusableNames() {
    assertTrue(Protocol.isWakeText("小麦，小麦！"));
    assertTrue(Protocol.isWakeText("哈喽小麦"));
    assertFalse(Protocol.isWakeText("小妹小妹"));
    assertFalse(Protocol.isWakeText("今天的天气不错"));
  }
}

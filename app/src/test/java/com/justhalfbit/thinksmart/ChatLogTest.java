package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class ChatLogTest {
  @Test
  public void migrationPreservesOldRecordAndIsIdempotent() {
    ChatLog log = new ChatLog("{}");
    log.migrate("a", "你：你好\n\n小麦：你好呀\n\n");
    log.migrate("a", "你：重复\n\n");
    ChatLog restored = new ChatLog(log.encode());
    assertEquals(2, restored.array("a").length());
    assertEquals("你好", restored.array("a").optJSONObject(0).optString("text"));
    assertEquals(0, restored.array("b").length());
  }

  @Test
  public void historyRefreshPreservesPendingAndDeduplicatesServerIds() throws Exception {
    ChatLog log = new ChatLog("{}");
    log.add("a", "user", "旧问题");
    log.add("a", "user", "新问题");
    JSONArray remote = new JSONArray("[{id:1,role:user,text:旧问题},{id:1,role:user,text:旧问题}]");
    log.merge("a", remote);
    log.merge("a", remote);
    assertEquals(2, log.array("a").length());
    assertEquals("新问题", log.array("a").optJSONObject(1).optString("text"));
  }

  @Test
  public void repeatedServerTextRemainsSeparateMessages() throws Exception {
    ChatLog log = new ChatLog("{}");
    log.merge("a", new JSONArray("[{id:1,role:user,text:你好},{id:2,role:user,text:你好}]"));
    assertEquals(2, log.array("a").length());
  }

  @Test
  public void modelSwitchMovesOnlyCurrentPendingTurn() {
    ChatLog log = new ChatLog("{}");
    log.add("a", "user", "new");
    log.moveLocalSince("a", "b", 1);
    assertEquals(0, log.array("a").length());
    assertEquals(1, log.array("b").length());
    log.clearAll();
    assertEquals("{}", log.encode());
  }

  @Test
  public void alreadySyncedRepeatedTextDoesNotConsumeNewPendingQuestion() throws Exception {
    ChatLog log = new ChatLog("{}");
    JSONArray old = new JSONArray("[{id:1,role:user,text:你好}]");
    log.merge("a", old);
    log.add("a", "user", "你好");
    log.merge("a", old);
    assertEquals(2, log.array("a").length());
  }

  @Test
  public void unmatchedLocalMessagesDoNotPreventLaterReconciliation() throws Exception {
    ChatLog log = new ChatLog("{}");
    log.add("a", "user", "仅存本机");
    log.add("a", "user", "新问题");
    log.merge("a", new JSONArray("[{id:2,role:user,text:新问题}]"));
    assertEquals(2, log.array("a").length());
    assertEquals("仅存本机", log.array("a").optJSONObject(1).optString("text"));
  }

  @Test
  public void streamedSentencesMergeAndSnapshotsStayImmutable() throws Exception {
    ChatLog log = new ChatLog("{}");
    log.add("a", "bot", "你好");
    JSONArray snapshot = log.array("a");
    log.appendBot("a", "欢迎回来");
    assertEquals("你好", snapshot.optJSONObject(0).optString("text"));
    log.merge("a", new JSONArray("[{id:4,role:bot,text:'你好，欢迎回来。'}]"));
    assertEquals(1, log.array("a").length());
  }

  @Test
  public void emptyHistoryRefreshKeepsSavedAndPendingMessagesAcrossRestart() throws Exception {
    ChatLog log = new ChatLog("{}");
    log.merge("a", new JSONArray("[{id:1,role:user,text:已保存的问题},{id:2,role:bot,text:已保存的回答}]"));
    log.add("a", "user", "尚未同步的问题");
    String saved = log.encode();
    for (int i = 0; i < 5; i++) {
      log = new ChatLog(saved);
      log.merge("a", new JSONArray());
      assertEquals(3, log.array("a").length());
      assertEquals("尚未同步的问题", log.array("a").optJSONObject(2).optString("text"));
      saved = log.encode();
    }
  }

  @Test
  public void pendingMessagesStayAtTheirTimeWhenNewerServerHistoryArrives() throws Exception {
    ChatLog log = new ChatLog("{a:[{id:local1,role:user,text:本地早消息,local:true,created:2000}]}");
    JSONArray remote = new JSONArray("[{id:3,role:bot,text:较晚回复,time:3},{id:1,role:user,text:较早问题,time:1}]");
    log.merge("a", remote);
    assertTexts(log.array("a"), "较早问题", "本地早消息", "较晚回复");
    log.merge("a", remote);
    assertTexts(new ChatLog(log.encode()).array("a"), "较早问题", "本地早消息", "较晚回复");
  }

  @Test
  public void existingUnsortedCacheDisplaysChronologicallyAndKeepsTimestampTiesStable() {
    ChatLog log = new ChatLog("{a:[{id:3,role:bot,text:晚,time:3},"
        + "{id:local1,role:user,text:早,local:true,created:1000},"
        + "{id:2,role:user,text:同刻一,time:2},"
        + "{id:local2,role:user,text:同刻二,local:true,created:2000}]}");
    assertTexts(log.array("a"), "早", "同刻一", "同刻二", "晚");
    assertTexts(new ChatLog(log.encode()).array("a"), "早", "同刻一", "同刻二", "晚");
    assertTrue(log.text("a").startsWith("你：早\n\n"));
  }

  @Test
  public void sortingSnapshotsAndSavingDoNotBreakStreamingReplyTarget() {
    ChatLog log = new ChatLog("{a:[{id:3,role:bot,text:服务端消息,time:3},"
        + "{id:local1,role:bot,text:正在播报,local:true,created:2000}]}");
    JSONArray before = log.array("a");
    ChatLog restored = new ChatLog(log.encode());
    restored.appendBot("a", "下一句");
    assertTexts(before, "正在播报", "服务端消息");
    assertTexts(restored.array("a"), "正在播报，下一句", "服务端消息");
  }

  private void assertTexts(JSONArray messages, String... expected) {
    assertEquals(expected.length, messages.length());
    for (int i = 0; i < expected.length; i++)
      assertEquals(expected[i], messages.optJSONObject(i).optString("text"));
  }
}

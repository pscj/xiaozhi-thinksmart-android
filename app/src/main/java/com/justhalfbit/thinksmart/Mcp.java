package com.justhalfbit.thinksmart;

import android.content.*;
import android.media.AudioManager;
import java.time.LocalDateTime;
import org.json.*;

final class Mcp {
  private static JSONObject tool(
      String name, String description, JSONObject properties, JSONArray required) {
    return VoiceService.json(
        "name",
        name,
        "description",
        description,
        "inputSchema",
        VoiceService.json("type", "object", "properties", properties, "required", required));
  }

  static JSONObject handle(VoiceService service, JSONObject request) {
    if (request == null || !request.has("id")) return null;
    Object id = request.opt("id");
    String method = request.optString("method");
    JSONObject result;
    try {
      if (method.equals("initialize"))
        result =
            VoiceService.json(
                "protocolVersion",
                "2024-11-05",
                "capabilities",
                VoiceService.json("tools", new JSONObject()),
                "serverInfo",
                VoiceService.json("name", "thinksmart-android", "version", "0.1.0"));
      else if (method.equals("tools/list")) {
        JSONObject percent = VoiceService.json("type", "integer", "minimum", 0, "maximum", 100),
            integer = VoiceService.json("type", "integer", "minimum", -100, "maximum", 100);
        JSONArray tools = new JSONArray();
        tools.put(
            tool(
                "self.audio_speaker.set_volume",
                "设置扬声器音量 0-100",
                VoiceService.json("volume", percent),
                new JSONArray().put("volume")));
        tools.put(
            tool("self.audio_speaker.get_volume", "读取扬声器音量", new JSONObject(), new JSONArray()));
        tools.put(
            tool(
                "self.screen.set_brightness",
                "设置当前应用屏幕亮度，brightness 或 change 二选一",
                VoiceService.json("brightness", percent, "change", integer),
                new JSONArray()));
        tools.put(tool("self.screen.get_brightness", "读取屏幕亮度", new JSONObject(), new JSONArray()));
        tools.put(
            tool(
                "self.sleep_mode.set",
                "设置睡眠时段 HH:MM，可跨午夜",
                VoiceService.json(
                    "enabled",
                    VoiceService.json("type", "boolean"),
                    "start",
                    VoiceService.json("type", "string"),
                    "end",
                    VoiceService.json("type", "string")),
                new JSONArray().put("enabled")));
        tools.put(tool("self.sleep_mode.get", "读取睡眠设置", new JSONObject(), new JSONArray()));
        tools.put(
            tool(
                "self.alarm.create",
                "创建设备本地闹钟：time 为 HH:MM；repeat 可选 once/daily/weekdays/weekly/monthly"
                    + "（默认 once），weekly 必须给 weekdays（1=周一…7=周日），monthly 必须给 month_day。"
                    + "创建新闹钟直接调用本工具，不要先调用 self.alarm.list；"
                    + "list 只用于用户询问现有闹钟时",
                VoiceService.json(
                    "time",
                    VoiceService.json("type", "string"),
                    "repeat",
                    VoiceService.json("type", "string"),
                    "weekdays",
                    VoiceService.json(
                        "type", "array", "items", VoiceService.json("type", "integer")),
                    "month_day",
                    VoiceService.json("type", "integer", "minimum", 1, "maximum", 31),
                    "label",
                    VoiceService.json("type", "string")),
                new JSONArray().put("time")));
        tools.put(tool("self.alarm.list", "列出全部闹钟", new JSONObject(), new JSONArray()));
        tools.put(
            tool(
                "self.alarm.cancel",
                "删除闹钟",
                VoiceService.json("id", VoiceService.json("type", "string")),
                new JSONArray().put("id")));
        tools.put(
            tool(
                "self.alarm.set_enabled",
                "开关闹钟",
                VoiceService.json(
                    "id",
                    VoiceService.json("type", "string"),
                    "enabled",
                    VoiceService.json("type", "boolean")),
                new JSONArray().put("id").put("enabled")));
        tools.put(
            tool(
                "self.timer.create",
                "创建设备本地倒计时：duration_sec 为总秒数（1…86400，最长 24 小时），label 可选。"
                    + "创建新倒计时直接调用本工具，不要先调用 self.timer.list；"
                    + "list 只用于用户询问现有倒计时时",
                VoiceService.json(
                    "duration_sec",
                    VoiceService.json("type", "integer", "minimum", 1, "maximum", 86400),
                    "label",
                    VoiceService.json("type", "string")),
                new JSONArray().put("duration_sec")));
        tools.put(
            tool(
                "self.timer.list",
                "列出全部倒计时（含调用时刻现算的剩余秒数）",
                new JSONObject(),
                new JSONArray()));
        tools.put(
            tool(
                "self.timer.cancel",
                "删除倒计时",
                VoiceService.json("id", VoiceService.json("type", "string")),
                new JSONArray().put("id")));
        tools.put(
            tool(
                "self.timer.pause",
                "暂停倒计时，返回冻结的剩余秒数",
                VoiceService.json("id", VoiceService.json("type", "string")),
                new JSONArray().put("id")));
        tools.put(
            tool(
                "self.timer.resume",
                "继续已暂停的倒计时",
                VoiceService.json("id", VoiceService.json("type", "string")),
                new JSONArray().put("id")));
        result = VoiceService.json("tools", tools);
      } else if (method.equals("tools/call")) {
        JSONObject p = request.getJSONObject("params"), a = p.optJSONObject("arguments");
        if (a == null) a = new JSONObject();
        String name = p.getString("name");
        SharedPreferences prefs = service.settings();
        AudioManager am = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
        JSONObject value;
        if (name.equals("self.audio_speaker.set_volume")) {
          int volume = Protocol.percent(a.getInt("volume"));
          int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
          am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(volume * max / 100f), 0);
          service.notifyVolume(volume);
          value = VoiceService.json("success", true, "volume", volume);
        } else if (name.equals("self.audio_speaker.get_volume"))
          value =
              VoiceService.json(
                  "volume",
                  Math.round(
                      am.getStreamVolume(AudioManager.STREAM_MUSIC)
                          * 100f
                          / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
        else if (name.equals("self.screen.set_brightness")
            || name.equals("self.screen.get_brightness")) {
          int level = prefs.getInt("brightness", 80);
          if (name.endsWith("set_brightness")) {
            if (a.has("brightness") == a.has("change"))
              throw new IllegalArgumentException("brightness 或 change 二选一");
            level =
                Protocol.percent(
                    a.has("brightness") ? a.getInt("brightness") : level + a.getInt("change"));
            prefs.edit().putInt("brightness", level).apply();
            service.notifySettings();
          }
          value = VoiceService.json("level", level, "dimmed", service.sleeping());
        } else if (name.startsWith("self.sleep_mode.")) {
          if (name.endsWith(".set")) {
            String start = a.optString("start", prefs.getString("sleepStart", "22:00")),
                end = a.optString("end", prefs.getString("sleepEnd", "07:00"));
            Protocol.minutes(start);
            Protocol.minutes(end);
            prefs
                .edit()
                .putBoolean("sleep", a.getBoolean("enabled"))
                .putString("sleepStart", start)
                .putString("sleepEnd", end)
                .apply();
            service.notifySettings();
          } else if (!name.endsWith(".get")) throw new IllegalArgumentException("未知工具");
          value =
              VoiceService.json(
                  "enabled",
                  prefs.getBoolean("sleep", false),
                  "start",
                  prefs.getString("sleepStart", "22:00"),
                  "end",
                  prefs.getString("sleepEnd", "07:00"),
                  "active",
                  service.sleeping());
        } else if (name.startsWith("self.alarm.")) {
          Alarms alarms = service.alarms();
          if (name.endsWith(".create")) {
            JSONObject item =
                alarms.create(
                    a.getString("time"),
                    a.optString("repeat", "once"),
                    a.optJSONArray("weekdays"),
                    a.optInt("month_day", 0),
                    a.optString("label", ""),
                    LocalDateTime.now());
            value =
                VoiceService.json(
                    "success", true, "id", item.optString("id"), "next_fire",
                    item.optString("next_fire"));
          } else if (name.endsWith(".list"))
            value = VoiceService.json("alarms", alarms.items());
          else if (name.endsWith(".cancel")) {
            alarms.cancel(a.getString("id"));
            value = VoiceService.json("success", true);
          } else if (name.endsWith(".set_enabled")) {
            JSONObject item =
                alarms.setEnabled(a.getString("id"), a.getBoolean("enabled"), LocalDateTime.now());
            value =
                VoiceService.json(
                    "success",
                    true,
                    "next_fire",
                    item.isNull("next_fire") ? JSONObject.NULL : item.optString("next_fire"));
          } else throw new IllegalArgumentException("未知工具");
        } else if (name.startsWith("self.timer.")) {
          Timers timers = service.timers();
          long nowMs = System.currentTimeMillis();
          if (name.endsWith(".create")) {
            JSONObject item =
                timers.create(
                    a.getInt("duration_sec"), a.optString("label", ""), nowMs);
            value =
                VoiceService.json(
                    "success",
                    true,
                    "id",
                    item.optString("id"),
                    "duration_sec",
                    item.optInt("duration_sec"),
                    "ends_at",
                    item.optLong("ends_at"),
                    "ends_at_local",
                    Timers.localIso(item.optLong("ends_at")));
          } else if (name.endsWith(".list"))
            value = VoiceService.json("timers", timers.snapshot(nowMs));
          else if (name.endsWith(".cancel")) {
            timers.cancel(a.getString("id"));
            value = VoiceService.json("success", true);
          } else if (name.endsWith(".pause")) {
            JSONObject item = timers.pause(a.getString("id"), nowMs);
            value =
                VoiceService.json(
                    "success", true, "remaining_sec", item.optLong("remaining_sec"));
          } else if (name.endsWith(".resume")) {
            JSONObject item = timers.resume(a.getString("id"), nowMs);
            value =
                VoiceService.json(
                    "success",
                    true,
                    "ends_at",
                    item.optLong("ends_at"),
                    "ends_at_local",
                    Timers.localIso(item.optLong("ends_at")));
          } else throw new IllegalArgumentException("未知工具");
        } else throw new IllegalArgumentException("未知工具 " + name);
        result =
            VoiceService.json(
                "content",
                new JSONArray().put(VoiceService.json("type", "text", "text", value.toString())),
                "isError",
                false);
      } else
        return VoiceService.json(
            "jsonrpc",
            "2.0",
            "id",
            id,
            "error",
            VoiceService.json("code", -32601, "message", "Unknown method"));
    } catch (Exception e) {
      result =
          VoiceService.json(
              "content",
              new JSONArray()
                  .put(
                      VoiceService.json(
                          "type",
                          "text",
                          "text",
                          e.getMessage() == null ? "参数错误" : e.getMessage())),
              "isError",
              true);
    }
    return VoiceService.json("jsonrpc", "2.0", "id", id, "result", result);
  }
}

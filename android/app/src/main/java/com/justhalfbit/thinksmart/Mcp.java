package com.justhalfbit.thinksmart;

import android.content.*;
import android.media.AudioManager;
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

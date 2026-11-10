package com.justhalfbit.thinksmart;

import java.util.*;
import org.json.*;

/** Structured messages. Local pending messages survive history refreshes. */
final class ChatLog {
  private final LinkedHashMap<String, List<JSONObject>> threads = new LinkedHashMap<>();

  ChatLog(String encoded) {
    try {
      JSONObject root = new JSONObject(encoded);
      Iterator<String> keys = root.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        JSONArray list = root.optJSONArray(key);
        if (list != null)
          for (int i = 0; i < list.length(); i++) {
            JSONObject m = list.optJSONObject(i);
            if (m != null) messages(key).add(m);
          }
      }
    } catch (JSONException ignored) {
    }
  }

  private List<JSONObject> messages(String entry) {
    List<JSONObject> list = threads.get(entry);
    if (list == null) {
      list = new ArrayList<>();
      threads.put(entry, list);
    }
    return list;
  }

  void migrate(String entry, String text) {
    if (threads.containsKey(entry) || text.isEmpty()) return;
    for (String line : text.split("\n\n")) {
      int split = line.indexOf('：');
      if (!line.trim().isEmpty())
        add(
            entry,
            split > 0 && line.startsWith("你") ? "user" : "bot",
            split > 0 ? line.substring(split + 1) : line);
      try {
        List<JSONObject> list = messages(entry);
        if (!list.isEmpty()) list.get(list.size() - 1).put("created", 0);
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  void add(String entry, String role, String text) {
    if (text.isEmpty()) return;
    JSONObject m = new JSONObject();
    try {
      m.put("id", "local:" + UUID.randomUUID());
      m.put("role", role);
      m.put("text", text);
      m.put("local", true);
      m.put("created", System.currentTimeMillis());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    List<JSONObject> list = messages(entry);
    list.add(m);
    trim(list);
  }

  void appendBot(String entry, String text) {
    List<JSONObject> list = messages(entry);
    if (!list.isEmpty()) {
      JSONObject last = list.get(list.size() - 1);
      if (last.optBoolean("local") && last.optString("role").equals("bot")) {
        try {
          last.put("text", last.optString("text") + "，" + text);
        } catch (JSONException e) {
          throw new IllegalStateException(e);
        }
        return;
      }
    }
    add(entry, "bot", text);
  }

  void markInterrupted(String entry) {
    List<JSONObject> list = messages(entry);
    if (!list.isEmpty()) {
      JSONObject last = list.get(list.size() - 1);
      if (last.optString("role").equals("bot")) {
        try {
          last.put("interrupted", true);
        } catch (JSONException e) {
          throw new IllegalStateException(e);
        }
      }
    }
  }

  private String normalized(String text) {
    return text.replaceAll("[\\p{P}\\p{S}\\s]", "");
  }

  void merge(String entry, JSONArray remote) {
    List<JSONObject> old = messages(entry), merged = new ArrayList<>();
    Set<String> ids = new HashSet<>(), known = new HashSet<>();
    for (JSONObject m : old) if (!m.optBoolean("local")) known.add(m.optString("id"));
    List<JSONObject> pending = new ArrayList<>();
    for (JSONObject m : old) if (m.optBoolean("local")) pending.add(m);
    // Match pending messages in sequence; never collapse repeated remote messages by text.
    int matchAfter = 0;
    Set<JSONObject> matched = Collections.newSetFromMap(new IdentityHashMap<JSONObject, Boolean>());
    for (int i = 0; i < remote.length(); i++) {
      JSONObject m = remote.optJSONObject(i);
      if (m == null || m.optString("text").isEmpty()) continue;
      String id = m.optString("id", "remote:" + i);
      if (!ids.add(id)) continue;
      merged.add(m);
      if (!known.contains(id)) {
        for (int j = matchAfter; j < pending.size(); j++) {
          JSONObject p = pending.get(j);
          if (p.optString("role").equals(m.optString("role"))
              && normalized(p.optString("text")).equals(normalized(m.optString("text")))
              && (!m.has("time")
                  || p.optLong("created") == 0
                  || m.optDouble("time") * 1000 >= p.optLong("created") - 30000)) {
            matched.add(p);
            matchAfter = j + 1;
            break;
          }
        }
      }
    }
    // Keep prior server messages if the response is a shorter history window.
    List<JSONObject> prefix = new ArrayList<>();
    for (JSONObject m : old)
      if (!m.optBoolean("local") && ids.add(m.optString("id"))) prefix.add(m);
    prefix.addAll(merged);
    for (JSONObject message : pending) if (!matched.contains(message)) prefix.add(message);
    trim(prefix);
    threads.put(entry, prefix);
  }

  void moveLocalSince(String from, String to, long since) {
    if (from.equals(to)) return;
    Iterator<JSONObject> it = messages(from).iterator();
    while (it.hasNext()) {
      JSONObject m = it.next();
      if (m.optBoolean("local") && m.optLong("created") >= since) {
        messages(to).add(m);
        it.remove();
      }
    }
  }

  JSONArray array(String entry) {
    try {
      return new JSONArray(new JSONArray(chronological(entry)).toString());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  String text(String entry) {
    StringBuilder out = new StringBuilder();
    for (JSONObject m : chronological(entry))
      out.append(m.optString("role").equals("user") ? "你" : "小麦")
          .append("：")
          .append(m.optString("text"))
          .append("\n\n");
    return out.toString();
  }

  void clear(String entry) {
    threads.remove(entry);
  }

  void clearAll() {
    threads.clear();
  }

  String encode() {
    JSONObject root = new JSONObject();
    try {
      // Keep ingestion order in storage: appendBot targets the active local reply.
      // Display order is computed separately, including for existing unsorted caches.
      for (String k : threads.keySet()) root.put(k, new JSONArray(messages(k)));
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return root.toString();
  }

  static long timestampMillis(JSONObject message) {
    return message.has("time")
        ? (long) (message.optDouble("time") * 1000)
        : message.optLong("created", 0);
  }

  private List<JSONObject> chronological(String entry) {
    List<JSONObject> ordered = new ArrayList<>(messages(entry));
    // Server timestamps use seconds; local timestamps use milliseconds.
    // Stable sorting preserves the order of messages with identical or missing times.
    ordered.sort(Comparator.comparingLong(ChatLog::timestampMillis));
    return ordered;
  }

  private void trim(List<JSONObject> list) {
    while (list.size() > 500) list.remove(0);
  }
}

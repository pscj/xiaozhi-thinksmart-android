package com.justhalfbit.thinksmart;

import okhttp3.HttpUrl;

/** Builds both service endpoints from the server's IP, without DNS lookups. */
final class ServerAddress {
  final String ip;
  final String websocket;
  final String management;

  ServerAddress(String input) {
    String host = input.trim();
    if (host.startsWith("[") && host.endsWith("]"))
      host = host.substring(1, host.length() - 1);
    if (host.contains(":")) {
      if (!host.matches("[0-9a-fA-F:.]+")) throw invalidIp();
    } else {
      String[] parts = host.split("\\.", -1);
      if (parts.length != 4) throw invalidIp();
      for (String part : parts) {
        if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255)
          throw invalidIp();
      }
    }
    HttpUrl base;
    try {
      base = new HttpUrl.Builder().scheme("http").host(host).port(16688).build();
    } catch (IllegalArgumentException e) {
      throw invalidIp();
    }
    ip = base.host();
    management = base.toString().replaceFirst("/$", "");
    websocket = base.newBuilder().port(16689).encodedPath("/xiaozhi/v1/")
        .build().toString().replaceFirst("^http", "ws");
  }

  static String savedIp(String ip, String websocket, String management) {
    if (!ip.isEmpty()) return ip;
    for (String address : new String[] {websocket, management}) {
      HttpUrl url = HttpUrl.parse(address.trim().replaceFirst("^ws", "http"));
      if (url == null) continue;
      try {
        return new ServerAddress(url.host()).ip;
      } catch (IllegalArgumentException ignored) {
        // An old hostname cannot be represented by the IP-only setting.
      }
    }
    return "";
  }

  private static IllegalArgumentException invalidIp() {
    return new IllegalArgumentException("请输入有效的服务器 IP，不需要协议、端口或路径");
  }
}

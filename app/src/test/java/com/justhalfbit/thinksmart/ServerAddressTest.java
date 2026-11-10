package com.justhalfbit.thinksmart;

import static org.junit.Assert.*;
import org.junit.Test;

public class ServerAddressTest {
  @Test
  public void generatesBothEndpointsFromOneIp() {
    ServerAddress address = new ServerAddress(" 192.168.1.10 ");
    assertEquals("192.168.1.10", address.ip);
    assertEquals("ws://192.168.1.10:16689/xiaozhi/v1/", address.websocket);
    assertEquals("http://192.168.1.10:16688", address.management);
  }

  @Test
  public void ipv6EndpointsHaveBrackets() {
    for (String input : new String[] {"2001:db8::1", "[2001:db8::1]"}) {
      ServerAddress address = new ServerAddress(input);
      assertEquals("2001:db8::1", address.ip);
      assertEquals("ws://[2001:db8::1]:16689/xiaozhi/v1/", address.websocket);
      assertEquals("http://[2001:db8::1]:16688", address.management);
    }
  }

  @Test
  public void rejectsUrlsPortsPathsAndInvalidIps() {
    for (String input : new String[] {"", "example.com", "999.1.1.1", "192.168.1",
        "http://192.168.1.10", "192.168.1.10:16688", "192.168.1.10/path", "2001:::1"}) {
      try {
        new ServerAddress(input);
        fail("Accepted invalid IP: " + input);
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().contains("服务器 IP"));
      }
    }
  }

  @Test
  public void prefillsOldUrlsWithoutReusingTheirPortsOrPaths() {
    assertEquals("192.168.1.10", ServerAddress.savedIp("",
        "ws://192.168.1.10:9000/old/", "http://192.168.1.11:8000"));
    assertEquals("2001:db8::1", ServerAddress.savedIp("", "",
        "http://[2001:db8::1]:16688"));
    assertEquals("192.168.1.12", ServerAddress.savedIp("192.168.1.12",
        "ws://192.168.1.10:16689/xiaozhi/v1/", ""));
    assertEquals("", ServerAddress.savedIp("", "", ""));
  }
}

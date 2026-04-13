package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.RequestAction;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.Test;

public class VirtualHostRouterTest {

  private static final HttpHandler DUMMY_HANDLER =
      (connection, request, writer) -> {
        throw new UnsupportedOperationException();
      };

  private static final HttpVirtualHost VHOST_A = new HttpVirtualHost(DUMMY_HANDLER);
  private static final HttpVirtualHost VHOST_B = new HttpVirtualHost(DUMMY_HANDLER);

  private static HttpRequest requestWithHost(String host) throws Exception {
    return new SimpleHttpRequest.Builder().setUri("*").addHeader("Host", host).build();
  }

  private static HttpRequest requestWithoutHost() throws Exception {
    return new SimpleHttpRequest.Builder().setUri("*").build();
  }

  // --- buildLookup ---

  @Test
  public void buildLookup_emptyHosts_returnsNull() {
    Function<String, HttpVirtualHost> lookup = VirtualHostRouter.buildLookup(Map.of());
    assertEquals(null, lookup.apply("anything"));
  }

  @Test
  public void buildLookup_exactMatch() {
    Function<String, HttpVirtualHost> lookup =
        VirtualHostRouter.buildLookup(Map.of("example", VHOST_A));
    assertEquals(VHOST_A, lookup.apply("example"));
  }

  @Test
  public void buildLookup_stripsPort() {
    Function<String, HttpVirtualHost> lookup =
        VirtualHostRouter.buildLookup(Map.of("example", VHOST_A));
    assertEquals(VHOST_A, lookup.apply("example:8080"));
  }

  @Test
  public void buildLookup_stripsLocalhostSuffix() {
    Function<String, HttpVirtualHost> lookup =
        VirtualHostRouter.buildLookup(Map.of("myapp", VHOST_A));
    assertEquals(VHOST_A, lookup.apply("myapp.localhost"));
  }

  @Test
  public void buildLookup_stripsLocalhostSuffixAndPort() {
    Function<String, HttpVirtualHost> lookup =
        VirtualHostRouter.buildLookup(Map.of("myapp", VHOST_A));
    assertEquals(VHOST_A, lookup.apply("myapp.localhost:3000"));
  }

  @Test
  public void buildLookup_fallsBackToDefault() {
    Map<String, HttpVirtualHost> hosts = new LinkedHashMap<>();
    hosts.put("default", VHOST_A);
    hosts.put("specific", VHOST_B);
    Function<String, HttpVirtualHost> lookup = VirtualHostRouter.buildLookup(hosts);
    assertEquals(VHOST_A, lookup.apply("unknown"));
  }

  @Test
  public void buildLookup_nullHostHeader_returnsDefault() {
    Function<String, HttpVirtualHost> lookup =
        VirtualHostRouter.buildLookup(Map.of("default", VHOST_A));
    assertEquals(VHOST_A, lookup.apply(null));
  }

  @Test
  public void buildLookup_nullHostHeader_noDefault_returnsNull() {
    Function<String, HttpVirtualHost> lookup =
        VirtualHostRouter.buildLookup(Map.of("specific", VHOST_A));
    assertEquals(null, lookup.apply(null));
  }

  @Test
  public void buildLookup_noMatch_noDefault_returnsNull() {
    Function<String, HttpVirtualHost> lookup =
        VirtualHostRouter.buildLookup(Map.of("specific", VHOST_A));
    assertEquals(null, lookup.apply("other"));
  }

  // --- buildConnectHandler ---

  @Test
  public void buildConnectHandler_withCustomHandler_returnsIt() {
    ConnectHandler custom = new ConnectHandler() {};
    ConnectHandler result = VirtualHostRouter.buildConnectHandler(custom, Map.of());
    assertEquals(custom, result);
  }

  @Test
  public void buildConnectHandler_customHandlerAndHosts_throws() {
    ConnectHandler custom = new ConnectHandler() {};
    Map<String, HttpVirtualHost> hosts = Map.of("host", VHOST_A);
    assertThrows(
        IllegalStateException.class, () -> VirtualHostRouter.buildConnectHandler(custom, hosts));
  }

  @Test
  public void buildConnectHandler_vhostBased_deniesConnect() {
    ConnectHandler handler =
        VirtualHostRouter.buildConnectHandler(null, Map.of("default", VHOST_A));
    assertTrue(handler.applyConnect("host", 443) instanceof ConnectDecision.Deny);
  }

  @Test
  public void buildConnectHandler_vhostBased_applyLocal_servesLocally() throws Exception {
    ConnectHandler handler =
        VirtualHostRouter.buildConnectHandler(null, Map.of("default", VHOST_A));
    RequestAction action = handler.applyLocal(requestWithHost("anything:80"));
    assertTrue(action instanceof RequestAction.ServeLocally);
  }

  @Test
  public void buildConnectHandler_vhostBased_applyProxy_servesLocally() throws Exception {
    ConnectHandler handler =
        VirtualHostRouter.buildConnectHandler(null, Map.of("default", VHOST_A));
    RequestAction action = handler.applyProxy(requestWithHost("anything:80"));
    assertTrue(action instanceof RequestAction.ServeLocally);
  }

  @Test
  public void buildConnectHandler_noHosts_deniesRequests() throws Exception {
    ConnectHandler handler = VirtualHostRouter.buildConnectHandler(null, Map.of());
    RequestAction action = handler.applyLocal(requestWithoutHost());
    assertTrue(action instanceof RequestAction.Deny);
  }

  // --- applyLocalFromVhosts ---

  @Test
  public void applyLocalFromVhosts_matchingHost_servesLocally() throws Exception {
    Function<String, HttpVirtualHost> lookup = host -> VHOST_A;
    RequestAction action =
        VirtualHostRouter.applyLocalFromVhosts(lookup, requestWithHost("host:80"));
    assertTrue(action instanceof RequestAction.ServeLocally);
    RequestAction.ServeLocally serve = (RequestAction.ServeLocally) action;
    assertEquals(VHOST_A.handler(), serve.handler());
  }

  @Test
  public void applyLocalFromVhosts_noMatch_denies() throws Exception {
    Function<String, HttpVirtualHost> lookup = host -> null;
    RequestAction action =
        VirtualHostRouter.applyLocalFromVhosts(lookup, requestWithHost("host:80"));
    assertTrue(action instanceof RequestAction.Deny);
  }
}

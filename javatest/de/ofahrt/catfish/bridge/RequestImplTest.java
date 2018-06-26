package de.ofahrt.catfish.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

import de.ofahrt.catfish.CollectionsUtils;
import de.ofahrt.catfish.HashConflictGenerator;
import de.ofahrt.catfish.InputStreams;
import de.ofahrt.catfish.api.Connection;
import de.ofahrt.catfish.api.HttpHeaderName;
import de.ofahrt.catfish.api.MalformedRequestException;
import de.ofahrt.catfish.api.SimpleHttpRequest;
import de.ofahrt.catfish.bridge.RequestImpl;
import de.ofahrt.catfish.bridge.ServletHelper;
import de.ofahrt.catfish.model.server.ResponsePolicy;

/**
 * Tests for {@link RequestImpl}.
 */
public class RequestImplTest {
  private RequestImpl toRequestImpl(SimpleHttpRequest.Builder builder) throws Exception {
    return new RequestImpl(
        builder.build(),
        new Connection(null, null, false),
        null,
        ResponsePolicy.ALLOW_NOTHING,
        null);
  }

  @Test(expected = IllegalStateException.class)
  public void missingUri() throws MalformedRequestException {
    new SimpleHttpRequest.Builder().build();
  }

  private RequestImpl empty() throws Exception {
    RequestImpl result = toRequestImpl(new SimpleHttpRequest.Builder().setUri("*"));
    return result;
  }

  private RequestImpl requestForBody(byte[] data) throws Exception {
    RequestImpl result = toRequestImpl(
        new SimpleHttpRequest.Builder()
            .setBody(data)
            .setUri("*"));
    return result;
  }

  private RequestImpl requestForHeader(String key, String value) throws Exception {
    RequestImpl result = toRequestImpl(
        new SimpleHttpRequest.Builder()
            .addHeader(key, value)
            .setUri("*"));
    return result;
  }

  @Test
  public void simpleEmpty() throws Exception {
    assertEquals(0, InputStreams.toByteArray(empty().getInputStream()).length);
    assertEquals("HTTP/0.9", empty().getProtocol());
    assertEquals("UNKNOWN", empty().getMethod());
  }

  @Test
  public void badRepeatedHost() throws Exception {
    try {
      new SimpleHttpRequest.Builder()
          .addHeader("Host", "a")
          .addHeader("Host", "b")
          .build();
      fail();
    } catch (MalformedRequestException expected) {
      assertNotNull(expected.getErrorResponse());
    }
  }

  @Test
  public void badHost() throws Exception {
    try {
      new SimpleHttpRequest.Builder()
          .addHeader("Host", "abc.com:12<")
          .build();
      fail();
    } catch (MalformedRequestException expected) {
    }
  }

  @Test
  public void getHeaders() throws Exception {
    assertEquals(Arrays.asList(),
        CollectionsUtils.toList(empty().getHeaders(HttpHeaderName.ACCEPT_CHARSET)));
    assertNull(empty().getHeader("key"));

    assertEquals(Arrays.asList("value"),
        CollectionsUtils.toList(requestForHeader("key", "value").getHeaders("key")));
    assertEquals(Arrays.asList("key"),
        CollectionsUtils.toList(requestForHeader("key", "value").getHeaderNames()));
    assertEquals("value", requestForHeader("key", "value").getHeader("key"));
  }

  @Test
  public void getInputStream() throws Exception {
  	RequestImpl request = requestForBody(new byte[] { -1, -1 });
  	byte[] data = InputStreams.toByteArray(request.getInputStream());
  	assertEquals(2, data.length);
  	assertEquals(-1, data[0]);
  	assertEquals(-1, data[1]);
  }

  @Test
  @SuppressWarnings("resource")
  public void getInputStreamByteByByte() throws Exception {
    RequestImpl request = requestForBody(new byte[] { 1, 10 });
    InputStream in = request.getInputStream();
    assertEquals(2, in.available());
    assertEquals(1, in.read());
    assertEquals(10, in.read());
    assertEquals(0, in.available());
    assertEquals(-1, in.read());
  }

  @Test
  @SuppressWarnings("resource")
  public void getInputStreamMixed() throws Exception {
    RequestImpl request = requestForBody(new byte[] { -1, 5 });
    InputStream in = request.getInputStream();
    assertEquals(255, in.read());
    byte[] data = InputStreams.toByteArray(in);
    assertEquals(1, data.length);
    assertEquals(5, data[0]);
  }

  @Test
  public void regressionTestForGetCompleteUrl() throws Exception {
    RequestImpl request = new RequestImpl(
        new SimpleHttpRequest.Builder()
            .setBody(new byte[] { -1, -1 })
            .setUri("*")
            .build(),
        new Connection(new InetSocketAddress(80), null, false),
        null,
        ResponsePolicy.ALLOW_NOTHING,
        null);
    ServletHelper.getCompleteUrl(request);
  }

  @Test
  public void locale() throws Exception {
    assertEquals(Locale.US, empty().getLocale());
    assertEquals(Locale.GERMAN, requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, "de").getLocale());
    assertEquals(Locale.GERMANY, requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, "de-de").getLocale());
    // Bad locale; fall back to default locale.
    assertEquals(Locale.US, requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, ";q=1").getLocale());
  }

  @Test
  public void multiLocale() throws Exception {
    assertEquals(Arrays.asList(Locale.US),
        CollectionsUtils.toList(empty().getLocales()));
    assertEquals(Arrays.asList(Locale.GERMAN),
        CollectionsUtils.toList(requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, "de").getLocales()));
    assertEquals(Arrays.asList(Locale.GERMAN, Locale.US),
        CollectionsUtils.toList(requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, "de, en-us").getLocales()));
    // Bad locale; return empty list. TODO: Is this correct?
    assertEquals(Arrays.asList(),
        CollectionsUtils.toList(requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, ";q=1").getLocales()));
  }

  @Test
  public void getRequestURL() throws Exception {
    RequestImpl request = toRequestImpl(
        new SimpleHttpRequest.Builder()
            .setUri("/")
            .addHeader("Host", "host:80"));
    assertEquals("http://host/", request.getRequestURL().toString());
  }

  @Test
  public void getRequestURLSecureOnNormalPort() throws Exception {
    RequestImpl request = new RequestImpl(
        new SimpleHttpRequest.Builder()
            .setUri("/")
            .addHeader("Host", "host:80").build(),
        new Connection(null, null, true),
        null,
        ResponsePolicy.ALLOW_NOTHING,
        null);
    assertEquals("https://host:80/", request.getRequestURL().toString());
  }

  @Test
  public void getRequestURLSecure() throws Exception {
    RequestImpl request = new RequestImpl(
        new SimpleHttpRequest.Builder()
            .setUri("/")
            .addHeader("Host", "host:443")
            .build(),
        new Connection(null, null, true),
        null,
        ResponsePolicy.ALLOW_NOTHING,
        null);
    assertEquals("https://host/", request.getRequestURL().toString());
  }

  // Only use lower-case letters to circumvent the canonicalizer.
  private static final String LOWER_CASE_ALPHA_AND_NUMERIC = "0123456789abcdefghijklmnopqrstuvwxyz";
  private static final List<String> REALLY_BAD_STRINGS =
      HashConflictGenerator.using(LOWER_CASE_ALPHA_AND_NUMERIC)
          .withHashCode(0).withLength(10).generateList(40000);

  @Test(timeout = 1000) // takes 14.290s with HashMap and 0.452s with TreeMap
  public void hashCollision() throws Exception {
    SimpleHttpRequest.Builder builder = new SimpleHttpRequest.Builder().setUri("*");
    for (String s : REALLY_BAD_STRINGS) {
      builder.addHeader(s, "x");
    }
    assertNotNull(builder.build());
  }
}

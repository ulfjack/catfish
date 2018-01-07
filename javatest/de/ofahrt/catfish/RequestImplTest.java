package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

import de.ofahrt.catfish.utils.HttpFieldName;
import de.ofahrt.catfish.utils.ServletHelper;

/**
 * Tests for {@link RequestImpl}.
 */
public class RequestImplTest {

  @Test(expected = IllegalStateException.class)
  public void missingUri() {
    new RequestImpl.Builder().build();
  }

  private RequestImpl empty() {
    RequestImpl result = new RequestImpl.Builder()
        .setUnparsedUri("*")
        .build();
    assertFalse(result.hasError());
    return result;
  }

  private RequestImpl requestForBody(byte[] data) {
    RequestImpl result = new RequestImpl.Builder()
        .setBody(data)
        .setUnparsedUri("*")
        .build();
    assertFalse(result.hasError());
    return result;
  }

  private RequestImpl requestForHeader(String key, String value) {
    RequestImpl result = new RequestImpl.Builder()
        .addHeader(key, value)
        .setUnparsedUri("*")
        .build();
    assertFalse(result.hasError());
    return result;
  }

  @Test
  public void simpleEmpty() throws Exception {
    assertEquals(0, InputStreams.toByteArray(empty().getInputStream()).length);
    assertEquals("HTTP/0.9", empty().getProtocol());
    assertEquals("UNKNOWN", empty().getMethod());
  }

  @Test
  public void badRepeatedHost() {
    RequestImpl request = new RequestImpl.Builder()
        .addHeader("Host", "a")
        .addHeader("Host", "b")
        .build();
    assertTrue(request.hasError());
    assertEquals("a", request.getHeader("Host"));
  }

  @Test
  public void badHost() {
    RequestImpl request = new RequestImpl.Builder()
        .addHeader("Host", "abc.com:12<")
        .build();
    assertTrue(request.hasError());
    assertNull(request.getHeader("Host"));
  }

  @Test
  public void getHeaders() {
    assertEquals(Arrays.asList(),
        CollectionsUtils.toList(empty().getHeaders(HttpFieldName.ACCEPT_CHARSET)));
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
  public void regressionTestForGetCompleteUrl() {
    RequestImpl request = new RequestImpl.Builder()
        .setLocalAddress(new InetSocketAddress(80))
        .setBody(new byte[] { -1, -1 })
        .setUnparsedUri("*")
        .build();
    assertFalse(request.hasError());
    ServletHelper.getCompleteUrl(request);
  }

  @Test
  public void locale() {
    assertEquals(Locale.US, empty().getLocale());
    assertEquals(Locale.GERMAN, requestForHeader(HttpFieldName.ACCEPT_LANGUAGE, "de").getLocale());
    assertEquals(Locale.GERMANY, requestForHeader(HttpFieldName.ACCEPT_LANGUAGE, "de-de").getLocale());
    // Bad locale; fall back to default locale.
    assertEquals(Locale.US, requestForHeader(HttpFieldName.ACCEPT_LANGUAGE, ";q=1").getLocale());
  }

  @Test
  public void multiLocale() {
    assertEquals(Arrays.asList(Locale.US),
        CollectionsUtils.toList(empty().getLocales()));
    assertEquals(Arrays.asList(Locale.GERMAN),
        CollectionsUtils.toList(requestForHeader(HttpFieldName.ACCEPT_LANGUAGE, "de").getLocales()));
    assertEquals(Arrays.asList(Locale.GERMAN, Locale.US),
        CollectionsUtils.toList(requestForHeader(HttpFieldName.ACCEPT_LANGUAGE, "de, en-us").getLocales()));
    // Bad locale; return empty list. TODO: Is this correct?
    assertEquals(Arrays.asList(),
        CollectionsUtils.toList(requestForHeader(HttpFieldName.ACCEPT_LANGUAGE, ";q=1").getLocales()));
  }

  @Test
  public void getRequestURL() throws Exception {
    RequestImpl request = new RequestImpl.Builder()
        .setUri(new URI("/"))
        .addHeader("Host", "host:80")
        .build();
    assertFalse(request.hasError());
    assertEquals("http://host/", request.getRequestURL().toString());
  }

  @Test
  public void getRequestURLSecureOnNormalPort() throws Exception {
    RequestImpl request = new RequestImpl.Builder()
        .setUri(new URI("/"))
        .setSecure(true)
        .addHeader("Host", "host:80")
        .build();
    assertFalse(request.hasError());
    assertEquals("https://host:80/", request.getRequestURL().toString());
  }

  @Test
  public void getRequestURLSecure() throws Exception {
    RequestImpl request = new RequestImpl.Builder()
        .setUri(new URI("/"))
        .setSecure(true)
        .addHeader("Host", "host:443")
        .build();
    assertFalse(request.hasError());
    assertEquals("https://host/", request.getRequestURL().toString());
  }

  // Only use lower-case letters to circumvent the canonicalizer.
  private static final String LOWER_CASE_ALPHA_AND_NUMERIC = "0123456789abcdefghijklmnopqrstuvwxyz";
  private static final List<String> REALLY_BAD_STRINGS =
      HashConflictGenerator.using(LOWER_CASE_ALPHA_AND_NUMERIC)
          .withHashCode(0).withLength(10).generateList(40000);

  @Test(timeout = 1000) // takes 14.290s with HashMap and 0.452s with TreeMap
  public void hashCollision() {
    RequestImpl.Builder builder = new RequestImpl.Builder()
        .setUnparsedUri("*");
    for (String s : REALLY_BAD_STRINGS) {
      builder.addHeader(s, "x");
    }
    assertNotNull(builder.build());
  }
}

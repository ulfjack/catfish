package de.ofahrt.catfish.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.CollectionsUtils;
import de.ofahrt.catfish.HashConflictGenerator;
import de.ofahrt.catfish.InputStreams;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.servlet.http.HttpSession;
import org.junit.Test;

/** Tests for {@link RequestImpl}. */
public class RequestImplTest {
  private static final HttpResponseWriter THROWING_WRITER =
      new HttpResponseWriter() {
        @Override
        public void commitBuffered(HttpResponse response) {
          throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream commitStreamed(HttpResponse response) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void abort() {
          throw new UnsupportedOperationException();
        }
      };

  private RequestImpl toRequestImpl(SimpleHttpRequest.Builder builder) throws Exception {
    return new RequestImpl(
        builder.build(), new Connection(null, null, false), null, THROWING_WRITER);
  }

  @Test(expected = MalformedRequestException.class)
  public void missingUri() throws MalformedRequestException {
    new SimpleHttpRequest.Builder().build();
  }

  private RequestImpl empty() throws Exception {
    RequestImpl result = toRequestImpl(new SimpleHttpRequest.Builder().setUri("*"));
    return result;
  }

  private RequestImpl requestForBody(byte[] data) throws Exception {
    RequestImpl result =
        toRequestImpl(
            new SimpleHttpRequest.Builder()
                .addHeader(HttpHeaderName.CONTENT_LENGTH, Integer.toString(data.length))
                .setBody(new HttpRequest.InMemoryBody(data))
                .setUri("*"));
    return result;
  }

  private RequestImpl requestForHeader(String key, String value) throws Exception {
    RequestImpl result =
        toRequestImpl(new SimpleHttpRequest.Builder().addHeader(key, value).setUri("*"));
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
      new SimpleHttpRequest.Builder().addHeader("Host", "a").addHeader("Host", "b").build();
      fail();
    } catch (MalformedRequestException expected) {
      assertNotNull(expected.getErrorResponse());
    }
  }

  @Test
  public void badHost() throws Exception {
    try {
      new SimpleHttpRequest.Builder().addHeader("Host", "abc.com:12<").build();
      fail();
    } catch (MalformedRequestException expected) {
    }
  }

  @Test
  public void getHeaders() throws Exception {
    assertEquals(
        Arrays.asList(),
        CollectionsUtils.toList(empty().getHeaders(HttpHeaderName.ACCEPT_CHARSET)));
    assertNull(empty().getHeader("key"));

    assertEquals(
        Arrays.asList("value"),
        CollectionsUtils.toList(requestForHeader("key", "value").getHeaders("key")));
    assertEquals(
        Arrays.asList("key"),
        CollectionsUtils.toList(requestForHeader("key", "value").getHeaderNames()));
    assertEquals("value", requestForHeader("key", "value").getHeader("key"));
  }

  @Test
  public void getInputStream() throws Exception {
    RequestImpl request = requestForBody(new byte[] {-1, -1});
    byte[] data = InputStreams.toByteArray(request.getInputStream());
    assertEquals(2, data.length);
    assertEquals(-1, data[0]);
    assertEquals(-1, data[1]);
  }

  @Test
  @SuppressWarnings("resource")
  public void getInputStreamByteByByte() throws Exception {
    RequestImpl request = requestForBody(new byte[] {1, 10});
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
    RequestImpl request = requestForBody(new byte[] {-1, 5});
    InputStream in = request.getInputStream();
    assertEquals(255, in.read());
    byte[] data = InputStreams.toByteArray(in);
    assertEquals(1, data.length);
    assertEquals(5, data[0]);
  }

  @Test
  public void regressionTestForGetCompleteUrl() throws Exception {
    RequestImpl request =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(new InetSocketAddress(80), null, false),
            null,
            THROWING_WRITER);
    ServletHelper.getCompleteUrl(request);
  }

  @Test
  public void locale() throws Exception {
    assertEquals(Locale.US, empty().getLocale());
    assertEquals(Locale.GERMAN, requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, "de").getLocale());
    assertEquals(
        Locale.GERMANY, requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, "de-de").getLocale());
    // Bad locale; fall back to default locale.
    assertEquals(Locale.US, requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, ";q=1").getLocale());
  }

  @Test
  public void multiLocale() throws Exception {
    assertEquals(Arrays.asList(Locale.US), CollectionsUtils.toList(empty().getLocales()));
    assertEquals(
        Arrays.asList(Locale.GERMAN),
        CollectionsUtils.toList(
            requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, "de").getLocales()));
    assertEquals(
        Arrays.asList(Locale.GERMAN, Locale.US),
        CollectionsUtils.toList(
            requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, "de, en-us").getLocales()));
    // Bad locale; return empty list. TODO: Is this correct?
    assertEquals(
        Arrays.asList(),
        CollectionsUtils.toList(
            requestForHeader(HttpHeaderName.ACCEPT_LANGUAGE, ";q=1").getLocales()));
  }

  @Test
  public void getRequestURL() throws Exception {
    RequestImpl request =
        toRequestImpl(new SimpleHttpRequest.Builder().setUri("/").addHeader("Host", "host:80"));
    assertEquals("http://host/", request.getRequestURL().toString());
  }

  @Test
  public void getRequestURLSecureOnNormalPort() throws Exception {
    RequestImpl request =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("/").addHeader("Host", "host:80").build(),
            new Connection(null, null, true),
            null,
            THROWING_WRITER);
    assertEquals("https://host:80/", request.getRequestURL().toString());
  }

  @Test
  public void getRequestURLSecure() throws Exception {
    RequestImpl request =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("/").addHeader("Host", "host:443").build(),
            new Connection(null, null, true),
            null,
            THROWING_WRITER);
    assertEquals("https://host/", request.getRequestURL().toString());
  }

  @Test
  public void getRemoteAddr_returnsNumericIp() throws Exception {
    InetAddress addr = InetAddress.getByAddress("my-host", new byte[] {1, 2, 3, 4});
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(null, new InetSocketAddress(addr, 5678), false),
            null,
            THROWING_WRITER);
    assertEquals("1.2.3.4", req.getRemoteAddr());
  }

  @Test
  public void getRemoteHost_returnsHostname() throws Exception {
    InetAddress addr = InetAddress.getByAddress("my-host", new byte[] {1, 2, 3, 4});
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(null, new InetSocketAddress(addr, 5678), false),
            null,
            THROWING_WRITER);
    assertEquals("my-host", req.getRemoteHost());
  }

  @Test
  public void getLocalAddr_returnsNumericIpWithoutSlash() throws Exception {
    InetAddress addr = InetAddress.getByAddress("local-host", new byte[] {127, 0, 0, 1});
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(new InetSocketAddress(addr, 8080), null, false),
            null,
            THROWING_WRITER);
    assertEquals("127.0.0.1", req.getLocalAddr());
  }

  @Test
  public void getIntHeader_returnsNegativeOneWhenAbsent() throws Exception {
    assertEquals(-1, empty().getIntHeader("X-Missing"));
  }

  @Test
  public void getIntHeader_returnsValueWhenPresent() throws Exception {
    assertEquals(42, requestForHeader("X-Count", "42").getIntHeader("X-Count"));
  }

  @Test
  public void getIntHeader_throwsNumberFormatExceptionForNonNumeric() throws Exception {
    assertThrows(
        NumberFormatException.class,
        () -> requestForHeader("X-Count", "bad").getIntHeader("X-Count"));
  }

  @Test
  public void getReader_withNullBody() throws Exception {
    RequestImpl req = toRequestImpl(new SimpleHttpRequest.Builder().setUri("*"));
    assertNotNull(req.getReader());
    assertNull(req.getReader().readLine());
  }

  @Test
  public void getContentLength_returnsNegativeOneWhenAbsent() throws Exception {
    assertEquals(-1, empty().getContentLength());
  }

  @Test
  public void getContentLength_returnsNegativeOneForMalformedHeader() throws Exception {
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder()
                .setUri("*")
                .addHeader(HttpHeaderName.CONTENT_LENGTH, "not-a-number")
                .setBody(new HttpRequest.InMemoryBody(new byte[0]))
                .build(),
            new Connection(null, null, false),
            null,
            THROWING_WRITER);
    assertEquals(-1, req.getContentLength());
  }

  @Test
  public void getAttribute_returnsNullWhenAbsent() throws Exception {
    assertNull(empty().getAttribute("missing"));
  }

  @Test
  public void setAttribute_getAttribute_roundTrip() throws Exception {
    RequestImpl req = empty();
    req.setAttribute("key", "value");
    assertEquals("value", req.getAttribute("key"));
  }

  @Test
  public void removeAttribute() throws Exception {
    RequestImpl req = empty();
    req.setAttribute("key", "value");
    req.removeAttribute("key");
    assertNull(req.getAttribute("key"));
  }

  @Test
  public void getAttributeNames_empty() throws Exception {
    assertFalse(empty().getAttributeNames().hasMoreElements());
  }

  @Test
  public void getAttributeNames_afterSet() throws Exception {
    RequestImpl req = empty();
    req.setAttribute("a", 1);
    assertEquals(Arrays.asList("a"), CollectionsUtils.toList(req.getAttributeNames()));
  }

  @Test
  public void getLocalName() throws Exception {
    InetAddress addr = InetAddress.getByAddress("local-host", new byte[] {127, 0, 0, 1});
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(new InetSocketAddress(addr, 8080), null, false),
            null,
            THROWING_WRITER);
    assertEquals("local-host", req.getLocalName());
  }

  @Test
  public void getLocalPort() throws Exception {
    InetAddress addr = InetAddress.getByAddress("local-host", new byte[] {127, 0, 0, 1});
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(new InetSocketAddress(addr, 8080), null, false),
            null,
            THROWING_WRITER);
    assertEquals(8080, req.getLocalPort());
  }

  @Test
  public void getRemotePort() throws Exception {
    InetAddress addr = InetAddress.getByAddress("client", new byte[] {10, 0, 0, 1});
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(null, new InetSocketAddress(addr, 12345), false),
            null,
            THROWING_WRITER);
    assertEquals(12345, req.getRemotePort());
  }

  @Test
  public void getContentType_returnsNullWhenAbsent() throws Exception {
    assertNull(empty().getContentType());
  }

  @Test
  public void getContentType_returnsValue() throws Exception {
    assertEquals(
        "text/plain", requestForHeader(HttpHeaderName.CONTENT_TYPE, "text/plain").getContentType());
  }

  @Test
  public void getQueryString_returnsNullForNoQuery() throws Exception {
    RequestImpl req = toRequestImpl(new SimpleHttpRequest.Builder().setUri("/path"));
    assertNull(req.getQueryString());
  }

  @Test
  public void getQueryString_returnsQuery() throws Exception {
    RequestImpl req = toRequestImpl(new SimpleHttpRequest.Builder().setUri("/path?a=1&b=2"));
    assertEquals("a=1&b=2", req.getQueryString());
  }

  @Test
  public void getParameterMap() throws Exception {
    RequestImpl req = toRequestImpl(new SimpleHttpRequest.Builder().setUri("/path?x=1&y=2"));
    assertEquals("1", req.getParameterMap().get("x"));
    assertEquals("2", req.getParameterMap().get("y"));
  }

  @Test
  public void getParameterValues_returnsNullWhenAbsent() throws Exception {
    assertNull(empty().getParameterValues("missing"));
  }

  @Test
  public void getParameterValues_returnsSingletonArray() throws Exception {
    RequestImpl req = toRequestImpl(new SimpleHttpRequest.Builder().setUri("/path?k=v"));
    String[] vals = req.getParameterValues("k");
    assertNotNull(vals);
    assertEquals(1, vals.length);
    assertEquals("v", vals[0]);
  }

  @Test
  public void getPathTranslated_returnsNull() throws Exception {
    assertNull(empty().getPathTranslated());
  }

  @Test
  public void getAuthType_returnsNull() throws Exception {
    assertNull(empty().getAuthType());
  }

  @Test
  public void isSecure_falseForHttp() throws Exception {
    assertFalse(empty().isSecure());
  }

  @Test
  public void isSecure_trueForHttps() throws Exception {
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(null, null, true),
            null,
            THROWING_WRITER);
    assertTrue(req.isSecure());
  }

  @Test
  public void getDateHeader_returnsNegativeOneWhenAbsent() throws Exception {
    assertEquals(-1, empty().getDateHeader("Date"));
  }

  @Test
  public void getDateHeader_parsesValidDate() throws Exception {
    RequestImpl req = requestForHeader("Date", "Sun, 06 Nov 1994 08:49:37 GMT");
    long millis = req.getDateHeader("Date");
    assertTrue(millis > 0);
  }

  @Test
  public void getDateHeader_returnsNegativeOneForBadDate() throws Exception {
    assertEquals(-1, requestForHeader("Date", "not-a-date").getDateHeader("Date"));
  }

  @Test
  public void supportGzipCompression_trueWhenPresent() throws Exception {
    assertTrue(
        requestForHeader(HttpHeaderName.ACCEPT_ENCODING, "gzip, deflate").supportGzipCompression());
  }

  @Test
  public void supportGzipCompression_falseWhenAbsent() throws Exception {
    assertFalse(empty().supportGzipCompression());
  }

  @Test
  public void supportGzipCompression_falseForDeflateOnly() throws Exception {
    assertFalse(
        requestForHeader(HttpHeaderName.ACCEPT_ENCODING, "deflate").supportGzipCompression());
  }

  @Test
  public void getScheme_http() throws Exception {
    assertEquals("http", empty().getScheme());
  }

  @Test
  public void getScheme_https() throws Exception {
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(null, null, true),
            null,
            THROWING_WRITER);
    assertEquals("https", req.getScheme());
  }

  @Test
  public void getServerName_fromHostHeader() throws Exception {
    assertEquals("example.com", requestForHeader("Host", "example.com:8080").getServerName());
  }

  @Test
  public void getServerName_fromHostHeaderWithoutPort() throws Exception {
    assertEquals("example.com", requestForHeader("Host", "example.com").getServerName());
  }

  @Test
  public void getServerName_fallsBackToLocalAddress() throws Exception {
    InetAddress addr = InetAddress.getByAddress("local", new byte[] {127, 0, 0, 1});
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(new InetSocketAddress(addr, 80), null, false),
            null,
            THROWING_WRITER);
    assertEquals("127.0.0.1", req.getServerName());
  }

  @Test
  public void getServerPort_fromHostHeader() throws Exception {
    assertEquals(8080, requestForHeader("Host", "example.com:8080").getServerPort());
  }

  @Test
  public void getServerPort_defaultsTo80ForHttp() throws Exception {
    assertEquals(80, requestForHeader("Host", "example.com").getServerPort());
  }

  @Test
  public void getServerPort_defaultsTo443ForHttps() throws Exception {
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").addHeader("Host", "example.com").build(),
            new Connection(null, null, true),
            null,
            THROWING_WRITER);
    assertEquals(443, req.getServerPort());
  }

  @Test
  public void getServerPort_fallsBackToLocalPort() throws Exception {
    InetAddress addr = InetAddress.getByAddress("local", new byte[] {127, 0, 0, 1});
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(new InetSocketAddress(addr, 9090), null, false),
            null,
            THROWING_WRITER);
    assertEquals(9090, req.getServerPort());
  }

  @Test
  public void getRequestURI() throws Exception {
    RequestImpl req = toRequestImpl(new SimpleHttpRequest.Builder().setUri("/foo/bar?q=1"));
    assertEquals("/foo/bar", req.getRequestURI());
  }

  @Test
  public void getMethod_returnsGetForHead() throws Exception {
    RequestImpl req =
        toRequestImpl(new SimpleHttpRequest.Builder().setMethod(HttpMethodName.HEAD).setUri("*"));
    assertEquals("GET", req.getMethod());
  }

  @Test
  public void getMethod_returnsActualMethod() throws Exception {
    RequestImpl req =
        toRequestImpl(new SimpleHttpRequest.Builder().setMethod(HttpMethodName.POST).setUri("*"));
    assertEquals("POST", req.getMethod());
  }

  @Test
  public void isRequestedSessionIdFromCookie_returnsTrue() throws Exception {
    assertTrue(empty().isRequestedSessionIdFromCookie());
  }

  @Test
  public void isRequestedSessionIdFromURL_returnsFalse() throws Exception {
    assertFalse(empty().isRequestedSessionIdFromURL());
  }

  @Test
  public void getSession_withSessionManager() throws Exception {
    SessionManager sm = new SessionManager();
    RequestImpl req =
        new RequestImpl(
            new SimpleHttpRequest.Builder().setUri("*").build(),
            new Connection(null, null, false),
            sm,
            THROWING_WRITER);
    HttpSession session = req.getSession(true);
    assertNotNull(session);
    // Same session on second call.
    assertEquals(session, req.getSession(true));
  }

  @Test
  public void getSession_falseReturnsNull() throws Exception {
    assertNull(empty().getSession(false));
  }

  @Test
  public void isRequestedSessionIdValid_falseWithNoSession() throws Exception {
    assertFalse(empty().isRequestedSessionIdValid());
  }

  @Test
  public void setCharacterEncoding() throws Exception {
    RequestImpl req = empty();
    req.setCharacterEncoding("ISO-8859-1");
    assertEquals("ISO-8859-1", req.getCharacterEncoding());
  }

  @Test
  public void getVersion() throws Exception {
    assertEquals(HttpVersion.HTTP_0_9, empty().getVersion());
  }

  @Test
  public void getPath() throws Exception {
    RequestImpl req = toRequestImpl(new SimpleHttpRequest.Builder().setUri("/foo/bar"));
    assertEquals("/foo/bar", req.getPath());
  }

  @Test
  public void mayKeepAlive() throws Exception {
    RequestImpl req =
        toRequestImpl(
            new SimpleHttpRequest.Builder()
                .setVersion(HttpVersion.HTTP_1_1)
                .setUri("*")
                .addHeader("Host", "localhost")
                .addHeader("Connection", "keep-alive"));
    assertTrue(req.mayKeepAlive());
  }

  // Only use lower-case letters to circumvent the canonicalizer.
  private static final String LOWER_CASE_ALPHA_AND_NUMERIC = "0123456789abcdefghijklmnopqrstuvwxyz";
  private static final List<String> REALLY_BAD_STRINGS =
      HashConflictGenerator.using(LOWER_CASE_ALPHA_AND_NUMERIC)
          .withHashCode(0)
          .withLength(10)
          .generateList(40000);

  @Test(timeout = 1000) // takes 14.290s with HashMap and 0.452s with TreeMap
  public void hashCollision() throws Exception {
    SimpleHttpRequest.Builder builder = new SimpleHttpRequest.Builder().setUri("*");
    for (String s : REALLY_BAD_STRINGS) {
      builder.addHeader(s, "x");
    }
    assertNotNull(builder.build());
  }
}

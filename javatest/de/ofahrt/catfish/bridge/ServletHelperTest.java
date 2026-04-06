package de.ofahrt.catfish.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.network.Connection;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.Test;

public class ServletHelperTest {

  private static FormData parseFormData(String contentType, String content) throws Exception {
    byte[] data = content.replace("\n", "\r\n").getBytes("ISO-8859-1");
    return ServletHelper.parseFormData(data.length, new ByteArrayInputStream(data), contentType);
  }

  @Test
  public void parseFormDataSimple() throws Exception {
    FormData formData =
        parseFormData(
            "multipart/form-data; boundary=abc",
            "--abc\n"
                + "Content-Disposition: form-data; name=\"a\"\n"
                + "\n"
                + "b\n"
                + "--abc--\n");
    assertEquals(0, formData.files.size());
    assertEquals(1, formData.data.size());
    assertEquals("b", formData.data.get("a"));
  }

  // ---- formatText ----

  @Test
  public void formatText_escapesHtmlEntities() {
    assertEquals("&lt;b&gt;", ServletHelper.formatText("<b>", false));
  }

  @Test
  public void formatText_escapesAmpersand() {
    assertEquals("a&amp;b", ServletHelper.formatText("a&b", false));
  }

  @Test
  public void formatText_escapesQuote() {
    assertEquals("&quot;hi&quot;", ServletHelper.formatText("\"hi\"", false));
  }

  @Test
  public void formatText_newlinesBecomeBreaks() {
    assertEquals("a<br/>b", ServletHelper.formatText("a\nb", false));
  }

  @Test
  public void formatText_carriageReturnStripped() {
    assertEquals("ab", ServletHelper.formatText("a\rb", false));
  }

  @Test
  public void formatText_fixedWrapsPre() {
    assertEquals("<pre>hello</pre>", ServletHelper.formatText("hello", true));
  }

  @Test
  public void formatText_plainTextPassesThrough() {
    assertEquals("hello world", ServletHelper.formatText("hello world", false));
  }

  // ---- getCompleteUrl ----

  @Test
  public void getCompleteUrl_withoutQuery() throws Exception {
    RequestImpl req = makeRequest("/path", null);
    assertEquals("http://localhost/path", ServletHelper.getCompleteUrl(req));
  }

  @Test
  public void getCompleteUrl_withQuery() throws Exception {
    RequestImpl req = makeRequest("/path?a=1", null);
    assertEquals("http://localhost/path?a=1", ServletHelper.getCompleteUrl(req));
  }

  // ---- throwableToString ----

  @Test
  public void throwableToString_containsMessage() {
    String result = ServletHelper.throwableToString(new RuntimeException("boom"));
    assertTrue(result.contains("boom"));
    assertTrue(result.contains("RuntimeException"));
  }

  // ---- requestToString ----

  @Test
  public void requestToString_containsMethodAndUrl() throws Exception {
    RequestImpl req = makeRequest("/hello", null);
    String result = ServletHelper.requestToString(req);
    assertNotNull(result);
    assertTrue(result.contains("UNKNOWN"));
    assertTrue(result.contains("/hello"));
  }

  // ---- parseFormData(HttpServletRequest) ----

  @Test
  public void parseFormData_noContentLength_returnsEmpty() throws Exception {
    RequestImpl req = makeRequest("*", null);
    FormData data = ServletHelper.parseFormData(req);
    assertEquals(0, data.data.size());
    assertEquals(0, data.files.size());
  }

  // ---- supportCompression ----

  @Test
  public void supportCompression_trueForGzip() throws Exception {
    RequestImpl req = makeRequest("*", "localhost");
    // supportCompression reads Accept-Encoding header via request.getHeader()
    // We can't easily set headers on RequestImpl after construction, but we can
    // test via an integration-style path with the header in the request.
    RequestImpl reqWithGzip = makeRequestWithHeader(HttpHeaderName.ACCEPT_ENCODING, "gzip");
    assertTrue(ServletHelper.supportCompression(reqWithGzip));
  }

  @Test
  public void supportCompression_falseWhenAbsent() throws Exception {
    assertFalse(ServletHelper.supportCompression(makeRequest("*", "localhost")));
  }

  // ---- getFilename ----

  @Test
  public void getFilename_extractsLastSegment() throws Exception {
    RequestImpl req = makeRequest("/foo/bar.html", "localhost");
    assertEquals("bar.html", ServletHelper.getFilename(req));
  }

  @Test
  public void getFilename_rootReturnsIndex() throws Exception {
    RequestImpl req = makeRequest("/", "localhost");
    assertEquals("index", ServletHelper.getFilename(req));
  }

  @Test
  public void getFilename_noSlash() throws Exception {
    RequestImpl req = makeRequest("/file.txt", "localhost");
    assertEquals("file.txt", ServletHelper.getFilename(req));
  }

  // ---- parseQuery ----

  @Test
  public void parseQuery_parsesKeyValue() throws Exception {
    RequestImpl req = makeRequest("/path?key=val&a=b", "localhost");
    java.util.Map<String, String> params = ServletHelper.parseQuery(req);
    assertEquals("val", params.get("key"));
    assertEquals("b", params.get("a"));
  }

  @Test
  public void parseQuery_emptyQuery() throws Exception {
    RequestImpl req = makeRequest("/path", "localhost");
    java.util.Map<String, String> params = ServletHelper.parseQuery(req);
    assertTrue(params.isEmpty());
  }

  // ---- getRequestText ----

  @Test
  public void getRequestText_returnsPreFormattedHtml() throws Exception {
    RequestImpl req = makeRequest("/test", "localhost");
    String result = ServletHelper.getRequestText(req);
    assertTrue(result.startsWith("<pre>"));
    assertTrue(result.endsWith("</pre>"));
  }

  // ---- parseFormData with url-encoded ----

  @Test
  public void parseFormData_urlEncoded() throws Exception {
    byte[] data = "name=value&foo=bar".getBytes("ISO-8859-1");
    FormData formData =
        ServletHelper.parseFormData(
            data.length, new ByteArrayInputStream(data), "application/x-www-form-urlencoded");
    assertEquals("value", formData.data.get("name"));
    assertEquals("bar", formData.data.get("foo"));
  }

  // ---- Helpers ----

  private static RequestImpl makeRequestWithHeader(String headerName, String headerValue)
      throws Exception {
    SimpleHttpRequest.Builder b =
        new SimpleHttpRequest.Builder().setUri("*").addHeader("Host", "localhost");
    b.addHeader(headerName, headerValue);
    InetAddress addr = InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1});
    return new RequestImpl(
        b.build(),
        new Connection(new InetSocketAddress(addr, 80), new InetSocketAddress(addr, 1234), false),
        null,
        null);
  }

  private static RequestImpl makeRequest(String uri, String host) throws Exception {
    SimpleHttpRequest.Builder b = new SimpleHttpRequest.Builder().setUri(uri);
    if (host != null) {
      b.addHeader("Host", host);
    } else {
      b.addHeader("Host", "localhost");
    }
    InetAddress addr = InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1});
    return new RequestImpl(
        b.build(),
        new Connection(new InetSocketAddress(addr, 80), new InetSocketAddress(addr, 1234), false),
        null,
        null);
  }
}

package de.ofahrt.catfish.client;

import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public abstract class HttpResponseParserTest {

  public abstract HttpResponse parse(byte[] data) throws Exception;

  public HttpResponse parse(String data) throws Exception {
    return parse(toBytes(data));
  }

  private byte[] toBytes(String data) {
    return data.replace("\n", "\r\n").getBytes(Charset.forName("ISO-8859-1"));
  }

  private String bodyAsString(HttpResponse response) {
    return new String(response.getBody(), StandardCharsets.UTF_8);
  }

  // ---- Happy-path tests ----

  @Test
  public void simple() throws Exception {
    HttpResponse response = parse("HTTP/1.1 200 OK\n\n");
    assertEquals(HttpVersion.HTTP_1_1, response.getProtocolVersion());
    assertEquals(200, response.getStatusCode());
    assertEquals("OK", response.getStatusMessage());
  }

  @Test
  public void simpleWithHeader() throws Exception {
    HttpResponse response = parse("HTTP/1.1 200 OK\nConnection: close\n\n");
    assertEquals("close", response.getHeaders().get("Connection"));
  }

  @Test
  public void simpleWithBody() throws Exception {
    HttpResponse response = parse("HTTP/1.1 200 OK\nContent-Length: 4\n\n0123");
    assertEquals("0123", bodyAsString(response));
  }

  @Test
  public void chunked() throws Exception {
    HttpResponse response =
        parse(
            "HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n"
                + "23\nThis is the data in the first chunk\n"
                + "1B\n and this is the second one\n"
                + "0\n\n");
    assertEquals(
        "This is the data in the first chunk and this is the second one", bodyAsString(response));
  }

  @Test
  public void chunkedWithLowerCase() throws Exception {
    HttpResponse response =
        parse("HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n" + "a\n123456789a\n" + "0\n\n");
    assertEquals("123456789a", bodyAsString(response));
  }

  // ---- Error-path tests ----

  @Test(expected = Exception.class)
  public void badLf_nonLfAfterCr() throws Exception {
    // \r in reason phrase sets expectLineFeed; next \r is not \n
    parse("HTTP/1.1 200 OK\r\rX".getBytes(StandardCharsets.ISO_8859_1));
  }

  // ---- RESPONSE_VERSION_HTTP ----

  @Test(expected = Exception.class)
  public void badVersionPrefix_notH() throws Exception {
    parse("XTTP/1.1 200 OK\n\n");
  }

  @Test(expected = Exception.class)
  public void badVersionPrefix_notFirstT() throws Exception {
    parse("HXTP/1.1 200 OK\n\n");
  }

  @Test(expected = Exception.class)
  public void badVersionPrefix_notSecondT() throws Exception {
    parse("HTXP/1.1 200 OK\n\n");
  }

  @Test(expected = Exception.class)
  public void badVersionPrefix_notP() throws Exception {
    parse("HTTX/1.1 200 OK\n\n");
  }

  @Test(expected = Exception.class)
  public void badVersionPrefix_notSlash() throws Exception {
    parse("HTTPX1.1 200 OK\n\n");
  }

  // ---- RESPONSE_VERSION_MAJOR ----

  @Test(expected = Exception.class)
  public void badMajorVersion_tooLong() throws Exception {
    parse("HTTP/1234567890.0 200 OK\n\n");
  }

  @Test(expected = Exception.class)
  public void badMajorVersion_empty() throws Exception {
    parse("HTTP/.1 200 OK\n\n");
  }

  @Test(expected = Exception.class)
  public void badMajorVersion_badChar() throws Exception {
    parse("HTTP/1x1 200 OK\n\n");
  }

  // ---- RESPONSE_VERSION_MINOR ----

  @Test(expected = Exception.class)
  public void badMinorVersion_tooLong() throws Exception {
    parse("HTTP/1.1234567890 200 OK\n\n");
  }

  @Test(expected = Exception.class)
  public void badMinorVersion_empty() throws Exception {
    parse("HTTP/1. 200 OK\n\n");
  }

  @Test(expected = Exception.class)
  public void badMinorVersion_badChar() throws Exception {
    parse("HTTP/1.1x 200 OK\n\n");
  }

  // ---- RESPONSE_CODE ----

  @Test(expected = Exception.class)
  public void badStatusCode_tooLong() throws Exception {
    parse("HTTP/1.1 2000 OK\n\n");
  }

  @Test(expected = Exception.class)
  public void badStatusCode_tooShort() throws Exception {
    parse("HTTP/1.1 20 OK\n\n");
  }

  @Test(expected = Exception.class)
  public void badStatusCode_leadingZero() throws Exception {
    parse("HTTP/1.1 020 OK\n\n");
  }

  @Test(expected = Exception.class)
  public void badStatusCode_nonDigit() throws Exception {
    parse("HTTP/1.1 20x OK\n\n");
  }

  // ---- RESPONSE_REASON_PHRASE ----

  @Test(expected = Exception.class)
  public void badReasonPhrase_tooLong() throws Exception {
    StringBuilder sb = new StringBuilder("HTTP/1.1 200 ");
    for (int i = 0; i < 1026; i++) {
      sb.append('X');
    }
    sb.append("\n\n");
    parse(sb.toString());
  }

  // ---- MESSAGE_HEADER_NAME ----

  @Test(expected = Exception.class)
  public void badHeaderName_colonWithEmptyName() throws Exception {
    parse("HTTP/1.1 200 OK\n:value\n\n");
  }

  @Test(expected = Exception.class)
  public void badHeaderName_unexpectedEol() throws Exception {
    // CRLF while header name buffer is non-empty
    parse("HTTP/1.1 200 OK\nFoo\n\n");
  }

  @Test(expected = Exception.class)
  public void badHeaderName_tooLong() throws Exception {
    StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\n");
    for (int i = 0; i < 1025; i++) {
      sb.append('X');
    }
    sb.append(": value\n\n");
    parse(sb.toString());
  }

  @Test(expected = Exception.class)
  public void badHeaderName_illegalChar() throws Exception {
    parse(("HTTP/1.1 200 OK\n\u0001bad: value\n\n").getBytes(StandardCharsets.ISO_8859_1));
  }

  // ---- MESSAGE_HEADER_VALUE ----

  @Test(expected = Exception.class)
  public void badHeaderValue_tooLong() throws Exception {
    StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\nX: ");
    for (int i = 0; i < 4098; i++) {
      sb.append('X');
    }
    sb.append("\n\n");
    parse(sb.toString());
  }

  // ---- MESSAGE_HEADER_NAME_OR_CONTINUATION ----

  @Test(expected = Exception.class)
  public void badHeaderNameOrContinuation_illegalChar() throws Exception {
    parse(("HTTP/1.1 200 OK\nX: value\n\u0001bad: value\n\n").getBytes(StandardCharsets.ISO_8859_1));
  }

  // ---- Content-Length errors ----

  @Test(expected = Exception.class)
  public void badContentLength_nonNumeric() throws Exception {
    parse("HTTP/1.1 200 OK\nContent-Length: abc\n\n");
  }

  @Test(expected = Exception.class)
  public void badContentLength_tooLarge() throws Exception {
    parse("HTTP/1.1 200 OK\nContent-Length: 1000001\n\n");
  }

  // ---- CHUNKED_CONTENT_LENGTH errors ----

  @Test(expected = Exception.class)
  public void badChunk_lengthFieldTooLong() throws Exception {
    parse("HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n123456789A\n");
  }

  @Test(expected = Exception.class)
  public void badChunk_illegalCharInLength() throws Exception {
    parse("HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\nX\n");
  }

  @Test(expected = Exception.class)
  public void badChunk_secondChunkTooLarge() throws Exception {
    // F4241 hex = 1000001, which exceeds maxContentLength (1000000)
    parse("HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n1\nA\nF4241\n");
  }

  // ---- CHUNKED_CONTENT_NEXT errors ----

  @Test(expected = Exception.class)
  public void badChunkedContentNext_illegalChar() throws Exception {
    // After chunk data is fully consumed, need CRLF; 'X' is illegal
    parse("HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n1\nAX\n0\n\n");
  }

  // ---- CHUNKED_CONTENT_END errors ----

  @Test(expected = Exception.class)
  public void badChunkedContentEnd_illegalChar() throws Exception {
    // After terminal chunk "0\r\n", need CRLF; 'X' is illegal
    parse("HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n1\nA\n0\nX\n");
  }
}

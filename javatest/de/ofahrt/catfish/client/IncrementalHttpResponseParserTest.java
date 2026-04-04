package de.ofahrt.catfish.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import de.ofahrt.catfish.model.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class IncrementalHttpResponseParserTest extends HttpResponseParserTest {

  private static final String VALID_STATUS_LINE = "HTTP/1.1 200 OK\r\n";

  @Override
  public HttpResponse parse(byte[] data) throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    parser.parse(data);
    return parser.getResponse();
  }

  @Test
  public void ignoreTrailingData() throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    byte[] data = "HTTP/1.1 200 OK\r\n\r\nTRAILING_DATA".getBytes(Charset.forName("ISO-8859-1"));
    assertEquals(data.length - 13, parser.parse(data));
  }

  @Test
  public void ignoreTrailingDataAfterBody() throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    byte[] data =
        "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n0123TRAILING_DATA"
            .getBytes(Charset.forName("ISO-8859-1"));
    assertEquals(data.length - 13, parser.parse(data));
  }

  // ---- Error path helpers and tests ----

  /** Parses {@code raw} and asserts the parser returned the error sentinel (1) without finishing. */
  private static void assertBadResponse(String raw) {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    byte[] data = raw.getBytes(StandardCharsets.ISO_8859_1);
    int result = parser.parse(data);
    assertEquals(1, result);
    assertFalse(parser.isDone());
  }

  // ---- expectLineFeed check ----

  @Test
  public void badLf_nonLfAfterCr() {
    // \r in the reason phrase sets expectLineFeed; next \r is not \n
    assertBadResponse("HTTP/1.1 200 OK\r\rX");
  }

  // ---- RESPONSE_VERSION_HTTP ----

  @Test
  public void badVersionPrefix_notH() {
    assertBadResponse("XTTP/1.1 200 OK\r\n\r\n");
  }

  @Test
  public void badVersionPrefix_notFirstT() {
    assertBadResponse("HXTP/1.1 200 OK\r\n\r\n");
  }

  @Test
  public void badVersionPrefix_notSecondT() {
    assertBadResponse("HTXP/1.1 200 OK\r\n\r\n");
  }

  @Test
  public void badVersionPrefix_notP() {
    assertBadResponse("HTTX/1.1 200 OK\r\n\r\n");
  }

  @Test
  public void badVersionPrefix_notSlash() {
    assertBadResponse("HTTPX1.1 200 OK\r\n\r\n");
  }

  // ---- RESPONSE_VERSION_MAJOR ----

  @Test
  public void badMajorVersion_tooLong() {
    // 10 digits triggers the >8 check on the 10th digit
    assertBadResponse("HTTP/1234567890.0 200 OK\r\n\r\n");
  }

  @Test
  public void badMajorVersion_empty() {
    // '.' immediately after 'HTTP/' with no digits
    assertBadResponse("HTTP/.1 200 OK\r\n\r\n");
  }

  @Test
  public void badMajorVersion_badChar() {
    // 'x' is neither a digit nor '.'
    assertBadResponse("HTTP/1x1 200 OK\r\n\r\n");
  }

  // ---- RESPONSE_VERSION_MINOR ----

  @Test
  public void badMinorVersion_tooLong() {
    // 10 digits triggers the >8 check on the 10th digit
    assertBadResponse("HTTP/1.1234567890 200 OK\r\n\r\n");
  }

  @Test
  public void badMinorVersion_empty() {
    // ' ' immediately after '.' with no digits
    assertBadResponse("HTTP/1. 200 OK\r\n\r\n");
  }

  @Test
  public void badMinorVersion_badChar() {
    // 'x' is neither a digit nor ' '
    assertBadResponse("HTTP/1.1x 200 OK\r\n\r\n");
  }

  // ---- RESPONSE_CODE ----

  @Test
  public void badStatusCode_tooLong() {
    // 4th digit triggers the >2-length check
    assertBadResponse("HTTP/1.1 2000 OK\r\n\r\n");
  }

  @Test
  public void badStatusCode_tooShort() {
    // ' ' after only 2 digits
    assertBadResponse("HTTP/1.1 20 OK\r\n\r\n");
  }

  @Test
  public void badStatusCode_leadingZero() {
    assertBadResponse("HTTP/1.1 020 OK\r\n\r\n");
  }

  @Test
  public void badStatusCode_nonDigit() {
    assertBadResponse("HTTP/1.1 20x OK\r\n\r\n");
  }

  // ---- RESPONSE_REASON_PHRASE ----

  @Test
  public void badReasonPhrase_tooLong() {
    // The >1024 check triggers after 1025 chars have been buffered
    StringBuilder sb = new StringBuilder("HTTP/1.1 200 ");
    for (int i = 0; i < 1026; i++) {
      sb.append('X');
    }
    sb.append("\r\n\r\n");
    assertBadResponse(sb.toString());
  }

  // ---- MESSAGE_HEADER_NAME ----

  @Test
  public void badHeaderName_colonWithEmptyName() {
    assertBadResponse(VALID_STATUS_LINE + ":value\r\n\r\n");
  }

  @Test
  public void badHeaderName_unexpectedEol() {
    // Bare \n (not preceded by \r) while header name buffer is non-empty
    assertBadResponse(VALID_STATUS_LINE + "Foo\n\r\n");
  }

  @Test
  public void badHeaderName_tooLong() {
    // The >=1024 check triggers after 1024 chars have been buffered
    StringBuilder sb = new StringBuilder(VALID_STATUS_LINE);
    for (int i = 0; i < 1025; i++) {
      sb.append('X');
    }
    sb.append(": value\r\n\r\n");
    assertBadResponse(sb.toString());
  }

  @Test
  public void badHeaderName_illegalChar() {
    // Control character \u0001 is not a token character
    assertBadResponse(VALID_STATUS_LINE + "\u0001bad: value\r\n\r\n");
  }

  // ---- MESSAGE_HEADER_VALUE ----

  @Test
  public void badHeaderValue_tooLong() {
    // The >4096 check triggers after 4097 chars have been buffered
    StringBuilder sb = new StringBuilder(VALID_STATUS_LINE + "X: ");
    for (int i = 0; i < 4098; i++) {
      sb.append('X');
    }
    sb.append("\r\n\r\n");
    assertBadResponse(sb.toString());
  }

  // ---- MESSAGE_HEADER_NAME_OR_CONTINUATION (illegal char) ----

  @Test
  public void badHeaderNameOrContinuation_illegalChar() {
    // Control char where a new header name is expected (after committing previous header)
    assertBadResponse(VALID_STATUS_LINE + "X: value\r\n\u0001bad: value\r\n\r\n");
  }

  // ---- Content-Length errors ----

  @Test
  public void badContentLength_nonNumeric() {
    assertBadResponse(VALID_STATUS_LINE + "Content-Length: abc\r\n\r\n");
  }

  @Test
  public void badContentLength_tooLarge() {
    assertBadResponse(VALID_STATUS_LINE + "Content-Length: 1000001\r\n\r\n");
  }

  // ---- CHUNKED_CONTENT_LENGTH errors ----

  @Test
  public void badChunk_lengthFieldTooLong() {
    // 10 hex digits triggers the >8-length check on the 10th digit
    assertBadResponse(VALID_STATUS_LINE + "Transfer-Encoding: chunked\r\n\r\n123456789A\r\n");
  }

  @Test
  public void badChunk_illegalCharInLength() {
    // 'X' is not a valid hex digit
    assertBadResponse(VALID_STATUS_LINE + "Transfer-Encoding: chunked\r\n\r\nX\r\n");
  }

  @Test
  public void badChunk_secondChunkTooLarge() {
    // F4241 hex = 1000001, which exceeds maxContentLength (1000000); content != null (second chunk)
    assertBadResponse(
        VALID_STATUS_LINE + "Transfer-Encoding: chunked\r\n\r\n" + "1\r\nA\r\n" + "F4241\r\n");
  }

  // ---- CHUNKED_CONTENT_NEXT errors ----

  @Test
  public void badChunkedContentNext_illegalChar() {
    // After chunk data is fully consumed, need CRLF; 'X' is illegal
    assertBadResponse(
        VALID_STATUS_LINE
            + "Transfer-Encoding: chunked\r\n\r\n"
            + "1\r\nA"
            + "X\r\n"
            + "0\r\n\r\n");
  }

  // ---- CHUNKED_CONTENT_END errors ----

  @Test
  public void badChunkedContentEnd_illegalChar() {
    // After "0\r\n" (terminal chunk), need CRLF; 'X' is illegal
    assertBadResponse(
        VALID_STATUS_LINE
            + "Transfer-Encoding: chunked\r\n\r\n"
            + "1\r\nA\r\n"
            + "0\r\nX\r\n");
  }
}

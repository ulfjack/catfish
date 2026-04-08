package de.ofahrt.catfish.fastcgi;

import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.fastcgi.IncrementalFcgiResponseParser.MalformedResponseException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class IncrementalFcgiResponseParserTest {

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static final class TestCallback implements IncrementalFcgiResponseParser.Callback {
    final List<String[]> headers = new ArrayList<>();
    final ByteArrayOutputStream data = new ByteArrayOutputStream();

    @Override
    public void addHeader(String key, String value) {
      headers.add(new String[] {key, value});
    }

    @Override
    public void addData(byte[] d, int offset, int length) {
      data.write(d, offset, length);
    }
  }

  @Test
  public void singleHeader_thenBody() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Content-Type: text/html\r\n\r\nHello"));
    assertEquals(1, cb.headers.size());
    assertEquals("Content-Type", cb.headers.get(0)[0]);
    assertEquals("text/html", cb.headers.get(0)[1]);
    assertEquals("Hello", cb.data.toString(StandardCharsets.ISO_8859_1));
  }

  @Test
  public void multipleHeaders() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Content-Type: text/html\r\nX-Custom: value\r\n\r\n"));
    assertEquals(2, cb.headers.size());
    assertEquals("Content-Type", cb.headers.get(0)[0]);
    assertEquals("X-Custom", cb.headers.get(1)[0]);
    assertEquals("value", cb.headers.get(1)[1]);
  }

  @Test
  public void headerValueTrimmed() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Key:   value   \r\n\r\n"));
    assertEquals(1, cb.headers.size());
    assertEquals("value", cb.headers.get(0)[1]);
  }

  @Test
  public void lfOnly_noCarriageReturn() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Key: value\n\nbody"));
    assertEquals(1, cb.headers.size());
    assertEquals("Key", cb.headers.get(0)[0]);
    assertEquals("value", cb.headers.get(0)[1]);
    assertEquals("body", cb.data.toString(StandardCharsets.ISO_8859_1));
  }

  @Test
  public void emptyHeaders_bodyOnly() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("\r\nBody data"));
    assertEquals(0, cb.headers.size());
    assertEquals("Body data", cb.data.toString(StandardCharsets.ISO_8859_1));
  }

  @Test
  public void incrementalParsing() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Key: val"));
    assertEquals(0, cb.headers.size());
    parser.parse(bytes("ue\r\n\r\ndata"));
    assertEquals(1, cb.headers.size());
    assertEquals("value", cb.headers.get(0)[1]);
    assertEquals("data", cb.data.toString(StandardCharsets.ISO_8859_1));
  }

  @Test
  public void bodyDataAfterHeaders_viaSeparateCall() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Key: value\r\n\r\n"));
    parser.parse(bytes("more data"));
    assertEquals("more data", cb.data.toString(StandardCharsets.ISO_8859_1));
  }

  @Test
  public void headerContinuation() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Key: value\r\n continued\r\n\r\n"));
    assertEquals(1, cb.headers.size());
    assertEquals("value continued", cb.headers.get(0)[1]);
  }

  @Test(expected = MalformedResponseException.class)
  public void colonWithoutFieldName_throws() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes(": value\r\n\r\n"));
  }

  @Test(expected = MalformedResponseException.class)
  public void badCharacterInHeaderName_throws() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Key Name: value\r\n\r\n"));
  }

  @Test(expected = MalformedResponseException.class)
  public void newlineInHeaderName_throws() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Key\r\n"));
  }

  @Test(expected = MalformedResponseException.class)
  public void crWithoutLf_throws() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Key: value\r\rx"));
  }

  @Test
  public void parseByteArray_convenienceMethod() throws Exception {
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    int consumed = parser.parse(bytes("\r\ndata"));
    assertEquals(6, consumed);
    assertEquals("data", cb.data.toString(StandardCharsets.ISO_8859_1));
  }

  @Test(expected = MalformedResponseException.class)
  public void delCharInHeaderName_throws() throws Exception {
    // DEL (0x7f) is a control character and illegal in token context.
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(new byte[] {'K', (byte) 0x7f, ':', ' ', 'v', '\r', '\n', '\r', '\n'});
  }

  @Test(expected = MalformedResponseException.class)
  public void separatorCharInHeaderName_throws() throws Exception {
    // Non-space separator characters are also illegal in a token.
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Key{Name: value\r\n\r\n"));
  }

  @Test(expected = MalformedResponseException.class)
  public void illegalCharAfterHeaderValueLine_throws() throws Exception {
    // After "Key: value\r\n", state is MESSAGE_HEADER_NAME_OR_CONTINUATION. A separator char at
    // the start of the next line is neither a continuation space, '\r', '\n', nor a token — it
    // should trigger the "Illegal character" throw at the tail of that case.
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Key: value\r\n:nope\r\n\r\n"));
  }

  @Test(expected = MalformedResponseException.class)
  public void crInsideHeaderName_expectingLf_butGotCr_throws() throws Exception {
    // \r followed by another \r (not \n) — the expectLineFeed check fires.
    TestCallback cb = new TestCallback();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(cb);
    parser.parse(bytes("Key\r\r"));
  }
}

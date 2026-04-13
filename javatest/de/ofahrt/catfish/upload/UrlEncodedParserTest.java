package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class UrlEncodedParserTest {

  private FormDataBody parse(String input) throws IOException {
    return new UrlEncodedParser().parse(input.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void singleParam() throws Exception {
    FormDataBody body = parse("key=value");
    assertEquals(1, body.size());
    assertEquals("key", body.get(0).getName());
    assertEquals("value", body.get(0).getValue());
  }

  @Test
  public void multipleParams() throws Exception {
    FormDataBody body = parse("a=1&b=2");
    assertEquals(2, body.size());
    assertEquals("a", body.get(0).getName());
    assertEquals("1", body.get(0).getValue());
    assertEquals("b", body.get(1).getName());
    assertEquals("2", body.get(1).getValue());
  }

  @Test
  public void urlDecodedKey() throws Exception {
    FormDataBody body = parse("hello+world=x");
    assertEquals(1, body.size());
    assertEquals("hello world", body.get(0).getName());

    FormDataBody body2 = parse("he%20llo=x");
    assertEquals(1, body2.size());
    assertEquals("he llo", body2.get(0).getName());
  }

  @Test
  public void urlDecodedValue() throws Exception {
    FormDataBody body = parse("key=hello%20world");
    assertEquals(1, body.size());
    assertEquals("hello world", body.get(0).getValue());
  }

  @Test
  public void emptyInput() throws Exception {
    FormDataBody body = parse("");
    assertEquals(0, body.size());
  }

  @Test
  public void parseWithOffsetAndLength() throws Exception {
    byte[] input = "padding_a=1&b=2_padding".getBytes(StandardCharsets.UTF_8);
    // Parse only "a=1&b=2" (offset 8, length 7)
    FormDataBody body = new UrlEncodedParser().parse(input, 8, 7);
    assertEquals(2, body.size());
    assertEquals("a", body.get(0).getName());
    assertEquals("1", body.get(0).getValue());
    assertEquals("b", body.get(1).getName());
    assertEquals("2", body.get(1).getValue());
  }
}

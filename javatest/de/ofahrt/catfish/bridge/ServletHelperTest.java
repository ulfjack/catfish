package de.ofahrt.catfish.bridge;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
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
}

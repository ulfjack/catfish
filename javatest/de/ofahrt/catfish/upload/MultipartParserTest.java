package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.util.List;
import java.util.TreeMap;
import org.junit.Test;
import de.ofahrt.catfish.HashConflictGenerator;
import de.ofahrt.catfish.upload.MultipartContainer.Part;

/**
 * Tests a parser for compliance with RFC 2046, section 5.1:
 * http://www.ietf.org/rfc/rfc2046.txt
 *
 * Including:
 * http://www.ietf.org/rfc/rfc2045.txt
 * http://www.ietf.org/rfc/rfc822.txt
 */
public class MultipartParserTest {

  public MultipartContainer parse(String contentType, byte[] data) throws Exception {
    IncrementalMultipartParser parser = new IncrementalMultipartParser(contentType);
    assertFalse(parser.isDone());
    int consumed = parser.parse(data, 0, data.length);
    assertEquals(data.length, consumed);
    assertTrue(parser.isDone());
    return parser.getContainer();
  }

  public MultipartContainer parse(String contentType, String data) throws Exception {
    byte[] bytes = data.replace("\n", "\r\n").getBytes();
  //  for (int i = 0; i < bytes.length; i++)
  //    System.out.print(" "+bytes[i]);
  //  System.out.println();
    return parse(contentType, bytes);
  }

  @Test
  public void parseEmptyContainer() throws Exception {
    MultipartContainer container = parse("multipart/form-data; boundary=abc",
        "--abc\n\n--abc--\n");
    assertEquals(1, container.size());
  }

  @Test
  public void parseEmptyContainerWithTwoParts() throws Exception {
    MultipartContainer container = parse("multipart/form-data; boundary=abc",
        "--abc\n\n--abc\n\n--abc--\n");
    assertEquals(2, container.size());
  }

  @Test
  public void parseEmptyContainerWithWeirdBoundary() throws Exception {
    MultipartContainer container = parse(
        "multipart/form-data; boundary=\"'()+_,-./:=? 0123456\"",
        "--'()+_,-./:=? 0123456\n\n--'()+_,-./:=? 0123456--");
    assertEquals(1, container.size());
  }

  @Test
  public void parseContainerWithHeader() throws Exception {
    MultipartContainer container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\na: b\n\n--abc--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("b", container.get(0).getField("a"));
  }

  @Test
  public void parseContainerWithTwoHeaders() throws Exception {
    MultipartContainer container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\na: b\nc: d\n\n--abc--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("b", container.get(0).getField("a"));
    assertEquals("d", container.get(0).getField("c"));
  }

  @Test
  public void parseContainerWithContinuationField() throws Exception {
    MultipartContainer container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\na: b\n c\n\n--abc--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("b c", container.get(0).getField("a"));
  }

  @Test
  public void parseContainerWithStandaloneCR() throws Exception {
    // Standalone CR is allowed in a field value.
    MultipartContainer container = parse(
        "multipart/form-data; boundary=abc",
      "--abc\na: \rb\n\n--abc--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("\rb", container.get(0).getField("a"));
  }

  @Test
  public void parseContainerWithStandaloneCR2() throws Exception {
    // Standalone CR is allowed in a field value.
    MultipartContainer container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\na: b\r\n\n--abc--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("b\r", container.get(0).getField("a"));
  }

  @Test
  public void parseContainerWithStandaloneCR3() throws Exception {
    // Standalone CR is allowed in a field value.
    MultipartContainer container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\na: b\r \n\n--abc--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("b\r", container.get(0).getField("a"));
  }

  @Test
  public void parseContainerWithStandaloneLF() throws Exception {
    // Standalone LF is allowed in a field value.
    MultipartContainer container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\r\na: \nb\r\n\r\n--abc--\r\n".getBytes());
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("\nb", container.get(0).getField("a"));
  }

  @Test
  public void parseFieldWithWhiteSpaceAtEnd() throws Exception {
    // Whitespace at the end of a field value should be ignored.
    MultipartContainer container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\na: b \n\n--abc--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("b", container.get(0).getField("a"));
  }

  @Test
  public void parseEmptyContainerWithTransportPadding() throws Exception {
    MultipartContainer container = parse("multipart/form-data; boundary=abc",
        "--abc    \n\n--abc--\n");
    assertEquals(1, container.size());
  }

  @Test(expected=MalformedMultipartException.class)
  public void illegalContainerWithOddStartBoundary() throws Exception {
    parse("multipart/form-data; boundary=abc", "--abc012\n\n--abc--\n");
  }

  @Test(expected=MalformedMultipartException.class)
  public void illegalContainerWithOddBoundary() throws Exception {
    parse("multipart/form-data; boundary=abc", "--abc\n\n--abc0123456\n\n--abc--\n");
  }

  @Test(expected=MalformedMultipartException.class)
  public void illegalContainerWithHyphenAtEnd() throws Exception {
    parse("multipart/form-data; boundary=abc", "--abc\n\n--abc-\n\n--abc--\n");
  }

  @Test
  public void validateWeirdBoundary() {
    // A valid boundary containing every special character allowed.
    char[] data = IncrementalMultipartParser.validateBoundary("'()+_,-./:=? 0123456");
    assertNotNull(data);
    assertEquals(24, data.length);
  }

  @Test(expected=IllegalArgumentException.class)
  public void illegalBoundaryTooLong() {
    IncrementalMultipartParser.validateBoundary(
        "12345678901234567890123456789012345678901234567890123456789012345678901");
  }

  @Test(expected=IllegalArgumentException.class)
  public void illegalCharacterInBoundary() {
    IncrementalMultipartParser.validateBoundary("\\");
  }

  @Test(expected=IllegalArgumentException.class)
  public void illegalSpaceAtBoundaryEnd() {
    IncrementalMultipartParser.validateBoundary("aa ");
  }

  @Test(expected=MalformedMultipartException.class)
  public void illegalBoundaryWithoutCR() throws Exception {
    parse("multipart/form-data; boundary=abc",
        "--abc\r-".getBytes());
  }

  @SuppressWarnings("unused")
  @Test(expected=IllegalArgumentException.class)
  public void notMultipartType() {
    new IncrementalMultipartParser("abc/def");
  }

  @SuppressWarnings("unused")
  @Test(expected=IllegalArgumentException.class)
  public void multipleBoundarySpecifications() {
    new IncrementalMultipartParser("multipart/form-data; boundary=abc; boundary=abc");
  }

  @SuppressWarnings("unused")
  @Test(expected=IllegalArgumentException.class)
  public void missingBoundarySpecification() {
    new IncrementalMultipartParser("multipart/form-data");
  }

  @Test(expected=MalformedMultipartException.class)
  public void containerWithIllegalEmptyFieldName() throws Exception {
    parse("multipart/form-data; boundary=abc", "--abc\n: a\n\n--abc--\n");
  }

  @Test(expected=MalformedMultipartException.class)
  public void containerWithIllegalFieldName() throws Exception {
    parse("multipart/form-data; boundary=abc", "--abc\n\001: a\n\n--abc--\n");
  }

  @Test(expected=MalformedMultipartException.class)
  public void containerWithIllegalSecondFieldName() throws Exception {
    parse("multipart/form-data; boundary=abc", "--abc\na: b\n\001: a\n\n--abc--\n");
  }

  @Test(expected=MalformedMultipartException.class)
  public void containerWithUnexpectedCRLF() throws Exception {
    parse("multipart/form-data; boundary=abc", "--abc\na\n\n--abc--\n");
  }

  @Test(expected=MalformedMultipartException.class)
  public void containerWithUnexpectedCR() throws Exception {
    parse("multipart/form-data; boundary=abc", "--abc\n\r\n\n--abc--\n");
  }

  private static final String LOWER_CASE_ALPHA_AND_NUMERIC =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final List<String> REALLY_BAD_STRINGS =
      HashConflictGenerator.using(LOWER_CASE_ALPHA_AND_NUMERIC)
          .withHashCode(0).withLength(10).generateList(40000);

  @Test(timeout = 1000)
  public void hashCollision() {
    TreeMap<String, String> data = new TreeMap<>();
    for (String s : REALLY_BAD_STRINGS) {
      data.put(s, "x");
    }
    assertNotNull(new Part(data));
  }
}

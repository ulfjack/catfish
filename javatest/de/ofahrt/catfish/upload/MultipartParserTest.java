package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.util.TreeMap;
import org.junit.Test;

/**
 * Tests a parser for compliance with RFC 2046, section 5.1:
 * http://www.ietf.org/rfc/rfc2046.txt
 *
 * Including:
 * http://www.ietf.org/rfc/rfc2045.txt
 * http://www.ietf.org/rfc/rfc822.txt
 */
public class MultipartParserTest {

  public FormDataBody parse(String contentType, byte[] data) throws MalformedMultipartException {
    IncrementalMultipartParser parser = new IncrementalMultipartParser(contentType);
    if (parser.isDone()) {
      parser.getParsedBody();
      fail();
    }
    int consumed = parser.parse(data, 0, data.length);
    assertTrue(parser.isDone());
    try {
      FormDataBody result = parser.getParsedBody();
      assertEquals(data.length, consumed);
      return result;
    } catch (MalformedMultipartException e) {
      throw e;
    }
  }

  public FormDataBody parse(String contentType, String data) throws Exception {
    byte[] bytes = data.replace("\n", "\r\n").getBytes();
  //  for (int i = 0; i < bytes.length; i++)
  //    System.out.print(" "+bytes[i]);
  //  System.out.println();
    return parse(contentType, bytes);
  }

//  @Test
//  public void parseEmptyContainer() throws Exception {
//    FormDataBody container = parse(
//        "multipart/form-data; boundary=abc",
//        "--abc\n\n--abc--\n");
//    assertEquals(1, container.size());
//  }
//
//  @Test
//  public void parseEmptyContainerWithTwoParts() throws Exception {
//    FormDataBody container = parse(
//        "multipart/form-data; boundary=abc",
//        "--abc\n\n--abc\n\n--abc--\n");
//    assertEquals(2, container.size());
//  }

  @Test
  public void parseEmptyContainerWithWeirdBoundary() throws Exception {
    FormDataBody container = parse(
        "multipart/form-data; boundary=\"'()+_,-./:=? 0123456\"",
        "--'()+_,-./:=? 0123456\nContent-Disposition: form-data; name=\"foo\"\n\n\n--'()+_,-./:=? 0123456--");
    assertEquals(1, container.size());
  }

  @Test
  public void parseContainerWithContentDisposition() throws Exception {
    FormDataBody container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\nContent-Disposition: form-data; name=\"foo\"\n\n\n--abc--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("foo", container.get(0).getName());
    assertEquals("", container.get(0).getValue());
  }

  @Test
  public void parseContainerWithContentDispositionCaseInsensitive() throws Exception {
    FormDataBody container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\ncONteNT-DIsPOSition: form-data; name=\"foo\"\n\n\n--abc--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("foo", container.get(0).getName());
    assertEquals("", container.get(0).getValue());
  }

  @Test
  public void parseContainerWithContentDispositionAndContentType() throws Exception {
    FormDataBody container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\nContent-Disposition: form-data\nContent-Type: text/plain\n\n\n--abc--\n");
    assertEquals(1, container.size());
    FormEntry entry = container.get(0);
    assertNotNull(entry);
    assertEquals("text/plain", entry.getContentType());
    assertArrayEquals(new byte[0], entry.getBody());
  }

  @Test
  public void parseContainerWithContinuationField() throws Exception {
    FormDataBody container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\nContent-Disposition: form-data;\n name=\"bar\"\n\n\n--abc--\n");
    assertEquals(1, container.size());
    FormEntry entry = container.get(0);
    assertNotNull(entry);
    assertEquals("bar", entry.getName());
  }

  @Test
  public void parseContainerWithStandaloneCR() throws Exception {
    // Standalone CR is allowed in a field value.
    FormDataBody container = parse(
        "multipart/form-data; boundary=abc",
      "--abc\nContent-Disposition: form-data\nContent-Type: \rb\n\n\n--abc--\n");
    assertEquals(1, container.size());
    FormEntry entry = container.get(0);
    assertNotNull(entry);
    assertEquals("\rb", entry.getContentType());
  }

  @Test
  public void parseContainerWithTrailingCR() throws Exception {
    // Standalone CR is allowed in a field value.
    FormDataBody container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\nContent-Disposition: form-data\nContent-Type: b\r\n\n\n--abc--\n");
    assertEquals(1, container.size());
    FormEntry entry = container.get(0);
    assertNotNull(entry);
    assertEquals("b\r", entry.getContentType());
  }

  @Test
  public void parseContainerWithStandaloneCR3() throws Exception {
    // Standalone CR is allowed in a field value.
    FormDataBody container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\nContent-Disposition: form-data\nContent-Type: b\r \n\n\n--abc--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("b\r", container.get(0).getContentType());
  }

  @Test
  public void parseContainerWithStandaloneLF() throws Exception {
    // Standalone LF is allowed in a field value.
    FormDataBody container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\r\nContent-Disposition: form-data\r\nContent-Type: \nb\r\n\r\n\r\n--abc--\r\n".getBytes());
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("\nb", container.get(0).getContentType());
  }

  @Test
  public void parseFieldWithWhiteSpaceAtEnd() throws Exception {
    // Whitespace at the end of a field value should be ignored.
    FormDataBody container = parse(
        "multipart/form-data; boundary=abc",
        "--abc\nContent-Disposition: form-data\nContent-Type: b \n\n\n--abc--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
    assertEquals("b", container.get(0).getContentType());
  }

  @Test
  public void parseEmptyContainerWithTransportPadding() throws Exception {
    FormDataBody container = parse("multipart/form-data; boundary=abc",
        "--abc    \nContent-Disposition:form-data; name=\"foo\"\n\n\n--abc--\n");
    assertEquals(1, container.size());
  }

  @Test
  public void parseContainerWithFileEntry() throws Exception {
    FormDataBody container = parse("multipart/form-data; boundary=abc",
        "--abc\nContent-Disposition: form-data; name=\"file\"\nContent-Type: text/plain\n\nDoh!\n--abc--\n");
    assertEquals(1, container.size());
    FormEntry entry = container.get(0);
    assertEquals("text/plain", entry.getContentType());
    assertArrayEquals(new byte[] { 'D', 'o', 'h', '!' }, entry.getBody());
  }

  @Test
  public void asMap() throws Exception {
    FormDataBody container = parse("multipart/form-data; boundary=abc",
        "--abc\n"
        + "Content-Disposition: form-data; name=\"foo\"\n\nDoh!"
        + "\n--abc\n"
        + "Content-Disposition: form-data; name=\"bar\"\n\nHo!"
        + "\n--abc\n"
        + "Content-Disposition: form-data\nContent-Type: text/plain\n\nMy fancy text!"
        + "\n--abc--\n");
    TreeMap<String, String> expected = new TreeMap<>();
    expected.put("foo", "Doh!");
    expected.put("bar", "Ho!");
    assertEquals(expected, container.formDataAsMap());
  }

  @Test(expected=MalformedMultipartException.class)
  public void containerWithIllegalStartBoundary() throws Exception {
    parse("multipart/form-data; boundary=abc", "--abc012\n\n--abc--\n");
  }

  @Test(expected=MalformedMultipartException.class)
  public void containerWithIllegalBoundary() throws Exception {
    parse("multipart/form-data; boundary=abc", "--abc\n\n--abc0123456\n\n--abc--\n");
  }

  @Test(expected=MalformedMultipartException.class)
  public void containerWithIllegalHyphenAtEnd() throws Exception {
    parse("multipart/form-data; boundary=abc", "--abc\n\n--abc-\n\n--abc--\n");
  }

  @Test
  public void boundaryWithEverySpecialCharacter() throws Exception {
    // A valid boundary containing every special character allowed.
    FormDataBody container = parse(
        "multipart/form-data; boundary=\"'()+_,-./:=? 0123456\"",
        "--'()+_,-./:=? 0123456\nContent-Disposition: form-data; name=\"baz\"\n\n\n--'()+_,-./:=? 0123456--\n");
    assertEquals(1, container.size());
    assertNotNull(container.get(0));
  }

  @Test(expected=MalformedMultipartException.class)
  public void boundaryTooLong() throws Exception {
    parse("multipart/form-data; boundary=12345678901234567890123456789012345678901234567890123456789012345678901", "");
  }

  @Test(expected=MalformedMultipartException.class)
  public void illegalCharacterInBoundary() throws Exception {
    parse("multipart/form-data; boundary=\"\\\"", "");
  }

  @Test(expected=MalformedMultipartException.class)
  public void illegalSpaceAtBoundaryEnd() throws Exception {
    parse("multipart/form-data; boundary=\"aa \"", "");
  }

  @Test(expected=MalformedMultipartException.class)
  public void illegalBoundaryWithoutCR() throws Exception {
    parse(
        "multipart/form-data; boundary=abc",
        "--abc\r-".getBytes());
  }

  @Test(expected=MalformedMultipartException.class)
  public void notMultipartType() throws Exception {
    parse("abc/def", "");
  }

  @Test(expected=MalformedMultipartException.class)
  public void multipleBoundarySpecifications() throws Exception {
    parse("multipart/form-data; boundary=abc; boundary=abc", "");
  }

  @Test(expected=MalformedMultipartException.class)
  public void missingBoundarySpecification() throws Exception {
    parse("multipart/form-data", "");
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
}

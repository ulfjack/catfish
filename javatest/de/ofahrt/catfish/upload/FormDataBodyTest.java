package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.Test;

public class FormDataBodyTest {

  @Test
  public void emptyConstantSizeIsZero() {
    assertEquals(0, FormDataBody.EMPTY.size());
  }

  @Test
  public void emptyConstantIteratorHasNoElements() {
    assertFalse(FormDataBody.EMPTY.iterator().hasNext());
  }

  @Test
  public void sizeMatchesPartCount() {
    FormDataBody body =
        new FormDataBody(Arrays.asList(new FormEntry("a", "1"), new FormEntry("b", "2")));
    assertEquals(2, body.size());
  }

  @Test
  public void getReturnsCorrectEntry() {
    FormEntry entry = new FormEntry("k", "v");
    FormDataBody body = new FormDataBody(Arrays.asList(entry));
    assertSame(entry, body.get(0));
  }

  @Test
  public void iteratorCoversAllEntries() {
    FormDataBody body =
        new FormDataBody(Arrays.asList(new FormEntry("a", "1"), new FormEntry("b", "2")));
    int count = 0;
    for (FormEntry ignored : body) {
      count++;
    }
    assertEquals(2, count);
  }

  @Test
  public void formDataAsMapIncludesFieldEntries() {
    FormDataBody body =
        new FormDataBody(Arrays.asList(new FormEntry("name", "alice"), new FormEntry("age", "30")));
    Map<String, String> map = body.formDataAsMap();
    assertEquals("alice", map.get("name"));
    assertEquals("30", map.get("age"));
  }

  @Test
  public void formDataAsMapExcludesFileEntries() {
    FormDataBody body =
        new FormDataBody(
            Arrays.asList(
                new FormEntry("field", "value"),
                new FormEntry("upload", "image/png", new byte[] {1, 2, 3})));
    Map<String, String> map = body.formDataAsMap();
    assertEquals(1, map.size());
    assertEquals("value", map.get("field"));
  }

  @Test
  public void formDataAsMapEmptyWhenOnlyFiles() {
    FormDataBody body =
        new FormDataBody(Arrays.asList(new FormEntry("f", "image/png", new byte[] {0})));
    assertTrue(body.formDataAsMap().isEmpty());
  }

  @Test
  public void parseFormData_nullBody_returnsEmpty() throws Exception {
    HttpRequest request = () -> "/";
    assertSame(FormDataBody.EMPTY, FormDataBody.parseFormData(request));
  }

  @Test
  public void parseFormData_missingContentType_returnsEmpty() throws Exception {
    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getUri() {
            return "/";
          }

          @Override
          public HttpRequest.Body getBody() {
            return new HttpRequest.InMemoryBody(new byte[0]);
          }
        };
    assertSame(FormDataBody.EMPTY, FormDataBody.parseFormData(request));
  }

  @Test
  public void parseFormData_urlEncoded_returnsParsedEntry() throws Exception {
    byte[] bodyBytes = "key=value".getBytes(StandardCharsets.UTF_8);
    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getUri() {
            return "/";
          }

          @Override
          public HttpRequest.Body getBody() {
            return new HttpRequest.InMemoryBody(bodyBytes);
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, "application/x-www-form-urlencoded");
          }
        };
    FormDataBody body = FormDataBody.parseFormData(request);
    Map<String, String> map = body.formDataAsMap();
    assertEquals("value", map.get("key"));
  }

  @Test
  public void parseFormData_multipart_returnsEntries() throws Exception {
    byte[] bodyBytes =
        "--abc\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n--abc--\r\n"
            .getBytes(StandardCharsets.ISO_8859_1);
    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getUri() {
            return "/";
          }

          @Override
          public HttpRequest.Body getBody() {
            return new HttpRequest.InMemoryBody(bodyBytes);
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, "multipart/form-data; boundary=abc");
          }
        };
    FormDataBody body = FormDataBody.parseFormData(request);
    assertEquals(1, body.size());
    assertEquals("field", body.get(0).getName());
    assertEquals("value", body.get(0).getValue());
  }

  @Test(expected = IOException.class)
  public void parseFormData_incompleteMulitpart_throws() throws Exception {
    // Incomplete multipart: no final boundary → parser is not done → throws
    byte[] bodyBytes =
        "--abc\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue"
            .getBytes(StandardCharsets.ISO_8859_1);
    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getUri() {
            return "/";
          }

          @Override
          public HttpRequest.Body getBody() {
            return new HttpRequest.InMemoryBody(bodyBytes);
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, "multipart/form-data; boundary=abc");
          }
        };
    FormDataBody.parseFormData(request);
  }

  @Test
  public void parseFormData_unknownMimeType_returnsEmpty() throws Exception {
    byte[] bodyBytes = "{}".getBytes(StandardCharsets.UTF_8);
    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getUri() {
            return "/";
          }

          @Override
          public HttpRequest.Body getBody() {
            return new HttpRequest.InMemoryBody(bodyBytes);
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, "application/json");
          }
        };
    assertSame(FormDataBody.EMPTY, FormDataBody.parseFormData(request));
  }
}

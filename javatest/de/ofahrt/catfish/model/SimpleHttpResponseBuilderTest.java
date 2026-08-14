package de.ofahrt.catfish.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class SimpleHttpResponseBuilderTest {

  @Test
  public void addHeaderCombinesListHeaderValues() throws Exception {
    // Content-Type may occur multiple times; values are combined with ", ".
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "text/html")
            .addHeader(HttpHeaderName.CONTENT_TYPE, "charset=utf-8")
            .build();
    assertEquals(
        "text/html, charset=utf-8", response.getHeaders().get(HttpHeaderName.CONTENT_TYPE));
  }

  @Test
  public void addHeaderMergesManyRepeatsInOrder() throws Exception {
    // Repeated list-valued headers accumulate via a StringBuilder (O(n) total, not O(n^2)); the
    // merged value must still read back in original order.
    SimpleHttpResponse.Builder builder = new SimpleHttpResponse.Builder().setStatusCode(200);
    StringBuilder expected = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      builder.addHeader(HttpHeaderName.CONTENT_TYPE, "v" + i);
      expected.append(i == 0 ? "" : ", ").append("v").append(i);
    }
    assertEquals(
        expected.toString(), builder.build().getHeaders().get(HttpHeaderName.CONTENT_TYPE));
  }

  @Test
  public void getHeaderReturnsMergedValueBeforeBuild() {
    // The origin-response parser reads Content-Length/Transfer-Encoding via getHeader mid-parse, so
    // the accumulator must be visible before build()/materialize().
    SimpleHttpResponse.Builder builder =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "a")
            .addHeader(HttpHeaderName.CONTENT_TYPE, "b");
    assertEquals("a, b", builder.getHeader(HttpHeaderName.CONTENT_TYPE));
  }

  @Test
  public void addHeaderThrowsOnDuplicateHost() throws Exception {
    // Host is in the non-list blacklist; adding it twice must throw.
    SimpleHttpResponse.Builder builder =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.HOST, "localhost");
    assertThrows(
        IllegalArgumentException.class,
        () -> builder.addHeader(HttpHeaderName.HOST, "localhost:8080"));
  }

  @Test
  public void addHeaderThrowsOnInvalidHost() {
    // A Host value containing a space is not a valid host:port.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SimpleHttpResponse.Builder()
                .setStatusCode(200)
                .addHeader(HttpHeaderName.HOST, "invalid host"));
  }
}

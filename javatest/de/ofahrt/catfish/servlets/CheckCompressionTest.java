package de.ofahrt.catfish.servlets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.client.TestingCatfishHttpClient;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.validator.HtmlValidator;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class CheckCompressionTest {

  @Test
  public void validHtml() throws Exception {
    TestingCatfishHttpClient client =
        TestingCatfishHttpClient.createClientForServlet(new CheckCompression());
    HttpResponse response = client.get("http://localhost/compression.html");
    assertNotNull(response);
    assertEquals(200, response.getStatusCode());
    new HtmlValidator().validate(new ByteArrayInputStream(response.getBody()));
  }

  @Test
  public void withGzipAcceptEncoding() throws Exception {
    TestingCatfishHttpClient client =
        TestingCatfishHttpClient.createClientForServlet(new CheckCompression());
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/compression.html")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader(HttpHeaderName.ACCEPT_ENCODING, "gzip")
            .build();
    HttpResponse response = client.send("http://localhost", request);
    assertNotNull(response);
    assertEquals(200, response.getStatusCode());
    String body = new String(response.getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("Your browser supports compression!"));
  }
}

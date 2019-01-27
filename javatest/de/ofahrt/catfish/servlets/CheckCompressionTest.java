package de.ofahrt.catfish.servlets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import java.io.ByteArrayInputStream;
import org.junit.Test;
import de.ofahrt.catfish.client.TestingCatfishHttpClient;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.validator.HtmlValidator;

public class CheckCompressionTest {

  @Test
  public void validHtml() throws Exception {
  	TestingCatfishHttpClient client = TestingCatfishHttpClient.createClientForServlet(new CheckCompression());
  	HttpResponse response = client.get("http://localhost/compression.html");
  	assertNotNull(response);
  	assertEquals(200, response.getStatusCode());
  	new HtmlValidator().validate(new ByteArrayInputStream(response.getBody()));
  }
}

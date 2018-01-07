package de.ofahrt.catfish.servlets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;

import org.junit.Test;

import de.ofahrt.catfish.client.CatfishHttpClient;
import de.ofahrt.catfish.client.HttpResponse;
import de.ofahrt.catfish.servlets.CheckCompression;
import de.ofahrt.catfish.utils.HtmlValidator;

public class CheckCompressionTest {

  @Test
  public void validHtml() throws IOException {
  	CatfishHttpClient client = CatfishHttpClient.createClientForServlet(new CheckCompression());
  	HttpResponse response = client.send("http://localhost/compression.html");
  	assertNotNull(response);
  	assertEquals(200, response.getStatusCode());
  	new HtmlValidator().validate(response.getInputStream());
  }
}

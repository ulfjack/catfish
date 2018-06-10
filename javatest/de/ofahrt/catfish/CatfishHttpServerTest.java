package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;

import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

import de.ofahrt.catfish.api.HttpResponse;

public class CatfishHttpServerTest {

  private static RequestImpl parse(String text) throws Exception {
  	byte[] data = text.getBytes("ISO-8859-1");
  	IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser(
  			new InetSocketAddress("127.0.0.1", 80), new InetSocketAddress("127.0.0.1", 1234), false);
  	int consumed = parser.parse(data);
  	assertEquals(data.length, consumed);
  	assertTrue("parser not done at end of input", parser.isDone());
  	return parser.getRequest();
  }

  private static HttpResponse createResponse(String text) throws Exception {
  	RequestImpl request = parse(text);
  	CatfishHttpServer server = new CatfishHttpServer(HttpServerListener.NULL);
  	HttpHost host = new HttpHost.Builder()
  	    .exact("/index", new TestServlet())
  	    .build();
  	server.addHttpHost("localhost", host);
  	server.setCompressionAllowed(true);
  	return server.createResponse(null, request).getResponse();
  }

  @Test
  public void noHostOnHttp11RequestResultsIn400() throws Exception {
  	HttpResponse response = createResponse("GET / HTTP/1.1\n\n");
  	assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getStatusCode());
  }

  @Test
  public void headRequestToExistingUrl() throws Exception {
    HttpResponse response = createResponse("HEAD /index HTTP/1.1\nHost: localhost\n\n");
  	assertEquals(HttpServletResponse.SC_OK, response.getStatusCode());
  }

  @Test
  public void headRequestToNonExistentUrl() throws Exception {
    HttpResponse response = createResponse("HEAD /nowhere HTTP/1.1\nHost: localhost\n\n");
  	assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatusCode());
  }

  @Test
  public void nonClosingServletWorksWithCompression() throws Exception {
    HttpResponse response = createResponse("GET /index HTTP/1.1\nHost: localhost\nAccept-Encoding: gzip\n\n");
  	assertEquals(HttpServletResponse.SC_OK, response.getStatusCode());
  }

  @Test
  public void emptyPost() throws Exception {
    HttpResponse response = createResponse("POST /index HTTP/1.1\nHost: localhost\n\n");
  	assertEquals(HttpServletResponse.SC_OK, response.getStatusCode());
  }

  @Test
  public void postWithContent() throws Exception {
    String content =
          "-----------------------------12184522311670376405338810566\n" // 58+1
        + "Content-Disposition: form-data; name=\"a\"\n" // 40+1
        + "\n" // 0+1
        + "b\n" // 1+1
        + "-----------------------------12184522311670376405338810566--\n" // 60+1
        + "";
    assertEquals(164, content.getBytes(Charset.forName("ISO-8859-1")).length);
    HttpResponse response = createResponse(
  			"POST /index HTTP/1.1\nHost: localhost\n"
  			+ "Content-Type: multipart/form-data; boundary=---------------------------13751323931886145875850488035\n"
  			+ "Content-Length: 164\n"
  			+ "\n"
  			+ content);
  	assertEquals(HttpServletResponse.SC_OK, response.getStatusCode());
  }
}

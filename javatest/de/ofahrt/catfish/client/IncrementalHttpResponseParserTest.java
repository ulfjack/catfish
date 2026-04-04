package de.ofahrt.catfish.client;

import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.model.HttpResponse;
import java.nio.charset.Charset;
import org.junit.Test;

public class IncrementalHttpResponseParserTest extends HttpResponseParserTest {

  @Override
  public HttpResponse parse(byte[] data) throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    parser.parse(data);
    return parser.getResponse();
  }

  @Test
  public void ignoreTrailingData() throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    byte[] data = "HTTP/1.1 200 OK\r\n\r\nTRAILING_DATA".getBytes(Charset.forName("ISO-8859-1"));
    assertEquals(data.length - 13, parser.parse(data));
  }

  @Test
  public void ignoreTrailingDataAfterBody() throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    byte[] data =
        "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n0123TRAILING_DATA"
            .getBytes(Charset.forName("ISO-8859-1"));
    assertEquals(data.length - 13, parser.parse(data));
  }
}

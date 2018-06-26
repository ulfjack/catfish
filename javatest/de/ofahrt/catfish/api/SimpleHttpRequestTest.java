package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;

public class SimpleHttpRequestTest {

  @Test
  public void noHostOnHttp11RequestResultsIn400() throws Exception {
    try {
      new SimpleHttpRequest.Builder()
          .setVersion(HttpVersion.HTTP_1_1)
          .setMethod(HttpMethodName.GET)
          .setUri("/")
          .build();
      fail();
    } catch (MalformedRequestException e) {
      assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), e.getErrorResponse().getStatusCode());
    }
  }

  @Test
  public void simpleErrorAlwaysContainsEmptyBody() throws Exception {
    try {
      new SimpleHttpRequest.Builder()
          .setError(HttpStatusCode.BAD_REQUEST, "foobar")
          .build();
      fail();
    } catch (MalformedRequestException e) {
      assertNotNull(e.getErrorResponse().getBody());
    }
  }
}

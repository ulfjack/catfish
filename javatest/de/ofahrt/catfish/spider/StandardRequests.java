package de.ofahrt.catfish.spider;

import java.net.URL;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;

public final class StandardRequests {
  public static HttpRequest get(URL url) throws MalformedRequestException {
    return new SimpleHttpRequest.Builder()
        .setMethod(HttpMethodName.GET)
        .setUri(url.getPath())
        .addHeader(HttpHeaderName.HOST, url.getHost())
        .build();
  }
}

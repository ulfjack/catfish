package de.ofahrt.catfish.spider;

import java.net.URL;

import de.ofahrt.catfish.api.HttpHeaderName;
import de.ofahrt.catfish.api.HttpMethodName;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.MalformedRequestException;
import de.ofahrt.catfish.api.SimpleHttpRequest;

public final class StandardRequests {
  public static HttpRequest get(URL url) throws MalformedRequestException {
    return new SimpleHttpRequest.Builder()
        .setMethod(HttpMethodName.GET)
        .setUri(url.getPath())
        .addHeader(HttpHeaderName.HOST, url.getHost())
        .build();
  }
}

package de.ofahrt.catfish.bridge;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import de.ofahrt.catfish.api.HttpResponse;

public class HttpResponseBridge {
  public void generate(HttpResponse response, HttpServletResponse servletResponse) throws IOException {
    servletResponse.setStatus(response.getStatusCode());
    for (Map.Entry<String, String> e : response.getHeaders()) {
      servletResponse.setHeader(e.getKey(), e.getValue());
    }
    try (OutputStream out = servletResponse.getOutputStream()) {
      response.writeBodyTo(out);
    }
  }
}

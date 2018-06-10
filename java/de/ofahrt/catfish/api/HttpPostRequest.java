package de.ofahrt.catfish.api;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import de.ofahrt.catfish.bridge.ServletHelper;

public final class HttpPostRequest extends HttpGetRequest {

  public static HttpPostRequest parse(HttpServletRequest request) throws IOException {
    return new HttpPostRequest(request);
  }

  private final Map<String, String> postParameters;

  public HttpPostRequest(HttpServletRequest request) throws IOException {
    super(request);
    this.postParameters = ServletHelper.parseFormData(request).data;
  }

  public Map<String, String> getPostParameters() {
    return postParameters;
  }

  public String getPostParameter(String s) {
    return postParameters.get(s);
  }

  public String getPostParameter(String s, String def) {
    String result = postParameters.get(s);
    return result != null ? result : def;
  }

  public Iterator<String> getPostKeyIterator() {
    return postParameters.keySet().iterator();
  }
}

package de.ofahrt.catfish.upload;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.utils.HttpContentType;

public final class FormDataBody implements HttpRequest.Body, Iterable<FormEntry> {
  public static final FormDataBody EMPTY = new FormDataBody(Collections.emptyList());

  public static FormDataBody parseFormData(HttpRequest request) throws IOException {
    if (request.getBody() == null) {
      return EMPTY;
    }
    byte[] body = ((HttpRequest.InMemoryBody) request.getBody()).toByteArray();
    String ctHeader = request.getHeaders().get(HttpHeaderName.CONTENT_TYPE);
    if (body != null && ctHeader != null) {
      String mimeType = HttpContentType.getMimeTypeFromContentType(ctHeader);

      if (mimeType.equals(HttpContentType.MULTIPART_FORMDATA)) {
        IncrementalMultipartParser parser = new IncrementalMultipartParser(ctHeader);
        parser.parse(body);
        if (!parser.isDone()) {
          throw new MalformedMultipartException("Bad data");
        }
        return parser.getParsedBody();
      }

      if (mimeType.equals(HttpContentType.WWW_FORM_URLENCODED)) {
        UrlEncodedParser parser = new UrlEncodedParser();
        return parser.parse(body);
      }
      return EMPTY;
    }
    return EMPTY;
  }

  private final List<FormEntry> parts;

  public FormDataBody(List<FormEntry> parts) {
    this.parts = parts;
  }

  public int size() {
    return parts.size();
  }

  public FormEntry get(int i) {
    return parts.get(i);
  }

  @Override
  public Iterator<FormEntry> iterator() {
    return parts.iterator();
  }

  public Map<String, String> formDataAsMap() {
    TreeMap<String, String> result = new TreeMap<>();
    for (FormEntry entry : parts) {
      if (!entry.isFile()) {
        result.put(entry.getName(), entry.getValue());
      }
    }
    return result;
  }
}

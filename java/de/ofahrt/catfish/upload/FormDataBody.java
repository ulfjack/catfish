package de.ofahrt.catfish.upload;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import de.ofahrt.catfish.model.HttpRequest;

public final class FormDataBody implements HttpRequest.Body, Iterable<FormEntry> {
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

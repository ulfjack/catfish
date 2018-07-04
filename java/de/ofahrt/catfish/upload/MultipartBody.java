package de.ofahrt.catfish.upload;

import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import de.ofahrt.catfish.model.HttpRequest;

public final class MultipartBody implements HttpRequest.Body, Iterable<MultipartBody.Part> {
  public static final class Part {
    private final Map<String, String> fields;

    public Part(Map<String, String> fields) {
      this.fields = new TreeMap<>(fields);
    }

    public String getField(String name) {
      return fields.get(name);
    }

    public String getContentType() {
      return null;
    }

    public InputStream getInputStream() {
      return null;
    }
  }

  private final Part[] parts;

  public MultipartBody(Part[] parts) {
    this.parts = parts;
  }

  public int size() {
    return parts.length;
  }

  public Part get(int i) {
    return parts[i];
  }

  @Override
  public Iterator<Part> iterator() {
    return new Iterator<Part>() {
      private int next = 0;

      @Override
      public boolean hasNext() {
        return next < parts.length;
      }

      @Override
      public Part next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return parts[next++];
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }
    };
  }
}

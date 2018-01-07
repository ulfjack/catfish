package de.ofahrt.catfish;

import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

final class MimeMultipartContainer implements Iterable<MimeMultipartContainer.Part> {

	public final static class Part {
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

  public MimeMultipartContainer(Part[] parts) {
  	this.parts = parts;
  }

  public int getPartCount() {
    return parts.length;
  }

  public Part getPart(int i) {
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

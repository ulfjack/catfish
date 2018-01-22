package de.ofahrt.catfish;

import java.util.Iterator;

final class PathFragmentIterator implements Iterator<String> {
  private String path;
  private int index;
  private int nextIndex;

  public PathFragmentIterator(String path) {
  	this.path = path;
  	this.index = 0;
  	this.nextIndex = path.indexOf('/', index+1);
  }

  @Override
  public boolean hasNext() {
  	return nextIndex >= 0;
  }

  @Override
  public String next() {
    if (nextIndex < 0) {
      throw new IllegalStateException();
    }
    String result = path.substring(index+1, nextIndex);
    index = nextIndex;
    nextIndex = path.indexOf('/', index+1);
  	return result;
  }

  public String getPath() {
    if (hasNext()) {
      throw new IllegalStateException();
    }
    return path.substring(0, index + 1);
  }

  public String getFilename() {
    if (hasNext()) {
      throw new IllegalStateException();
    }
    return path.substring(index + 1);
  }
}

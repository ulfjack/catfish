package de.ofahrt.catfish;

final class PathTracker {

  private String path;
  private int index;
  private int nextIndex;

  public PathTracker(String path) {
  	this.path = path;
  	index = 0;
  	nextIndex = path.indexOf('/', index+1);
  }

  public boolean hasNextPath() {
  	return nextIndex >= 0;
  }

  public String getNextPath() {
  	return path.substring(index+1, nextIndex);
  }

  public void advance() {
  	if (nextIndex < 0) {
  	  throw new IllegalStateException();
  	}
  	index = nextIndex;
  	nextIndex = path.indexOf('/', index+1);
  }

  public String getPath() {
    return path.substring(0, index);
  }

  public String getFilename() {
    return path.substring(index + 1);
  }
}

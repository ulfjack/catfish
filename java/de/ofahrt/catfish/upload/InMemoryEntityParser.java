package de.ofahrt.catfish.upload;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpRequest.Body;
import de.ofahrt.catfish.model.server.PayloadParser;

public final class InMemoryEntityParser implements PayloadParser {
  private final byte[] content;
  private int contentIndex;

  public InMemoryEntityParser(int expectedContentLength) {
    this.content = new byte[expectedContentLength];
  }

  @Override
  public int parse(byte[] input, int offset, int length) {
    int maxCopy = Math.min(length, content.length - contentIndex);
    System.arraycopy(input, offset, content, contentIndex, maxCopy);
    contentIndex += maxCopy;
    return maxCopy;
  }

  @Override
  public boolean isDone() {
    return contentIndex >= content.length;
  }

  @Override
  public Body getParsedBody() {
    return new HttpRequest.InMemoryBody(content);
  }
}

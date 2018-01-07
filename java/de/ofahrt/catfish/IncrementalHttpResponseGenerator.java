package de.ofahrt.catfish;

final class IncrementalHttpResponseGenerator {

  private final ResponseImpl response;
  private final byte[] header;
  private final byte[] body;
  private int usedData;

  public IncrementalHttpResponseGenerator(ResponseImpl response) {
    this.response = response;
    header = response.getHeaders();
    body = response.getBody();
    usedData = 0;
  }

  public ResponseImpl getResponse() {
    return response;
  }

  public int generate(byte[] dest, int offset, int length) {
    int total = 0;
    if (usedData < header.length) {
      int max = Math.min(header.length - usedData, length);
      if (max == 0) {
        return 0;
      }
      System.arraycopy(header, usedData, dest, offset, max);
      usedData += max;
      if (max == length) {
        return max;
      }
      total += max;
    }
    if (body == null) {
      return total;
    }

    int relativeIndex = usedData - header.length;
    int max = Math.min(body.length - relativeIndex, length - total);
    if (max == 0) {
      return 0;
    }
    System.arraycopy(body, relativeIndex, dest, offset + total, max);
    usedData += max;
    total += max;
    return total;
  }
}

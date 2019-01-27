package de.ofahrt.catfish.client;

import java.nio.ByteBuffer;

import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpRequest.InMemoryBody;

final class HttpRequestGeneratorBuffered extends HttpRequestGenerator {
  public static HttpRequestGeneratorBuffered create(HttpRequest request, boolean includeBody) {
    if (request.getBody() == null) {
      throw new IllegalArgumentException();
    }
    byte[] body = includeBody ? ((InMemoryBody) request.getBody()).toByteArray() : EMPTY_BYTE_ARRAY;
    HttpHeaders headers = request.getHeaders();
    byte[][] data = new byte[][] {
      requestLineToByteArray(request),
      headersToByteArray(headers),
      body
    };
    return new HttpRequestGeneratorBuffered(request, data);
  }

  public static HttpRequestGeneratorBuffered createWithBody(HttpRequest request) {
    return create(request, true);
  }

  private final HttpRequest request;

  private final byte[][] data;
  private int currentBlock;
  private int currentIndex;

  HttpRequestGeneratorBuffered(HttpRequest request, byte[][] data) {
    this.request = request;
    this.data = data;
  }

  @Override
  public HttpRequest getRequest() {
    return request;
  }

  @Override
  public ContinuationToken generate(ByteBuffer outputBuffer) {
    if (currentBlock >= data.length) {
      return ContinuationToken.STOP;
    }
    int totalBytesCopied = 0;
    while (outputBuffer.hasRemaining()) {
      int bytesCopyCount = Math.min(outputBuffer.remaining(), data[currentBlock].length - currentIndex);
      outputBuffer.put(data[currentBlock], currentIndex, bytesCopyCount);
      totalBytesCopied += bytesCopyCount;
      currentIndex += bytesCopyCount;
      if (currentIndex >= data[currentBlock].length) {
        currentBlock++;
        currentIndex = 0;
      }
      if (currentBlock >= data.length) {
        break;
      }
    }
    if ((totalBytesCopied == 0) && (currentBlock >= data.length)) {
      // There wasn't actually any data left.
      return ContinuationToken.STOP;
    }
    return ContinuationToken.CONTINUE;
  }

  @Override
  public void close() {
  }
}
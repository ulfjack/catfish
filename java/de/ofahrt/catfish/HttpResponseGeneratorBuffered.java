package de.ofahrt.catfish;

import java.nio.ByteBuffer;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpResponse;

final class HttpResponseGeneratorBuffered extends HttpResponseGenerator {
  public static HttpResponseGeneratorBuffered create(HttpResponse response, boolean includeBody) {
    if (response.getBody() == null) {
      throw new IllegalArgumentException();
    }
    byte[] body = includeBody ? response.getBody() : EMPTY_BYTE_ARRAY;
    HttpHeaders headers = response.getHeaders();
    byte[][] data = new byte[][] {
      statusLineToByteArray(response),
      headersToByteArray(headers),
      body
    };
    return new HttpResponseGeneratorBuffered(response, data);
  }

  public static HttpResponseGeneratorBuffered createWithBody(HttpResponse response) {
    return create(response, true);
  }

  private final HttpResponse response;

  private final byte[][] data;
  private int currentBlock;
  private int currentIndex;

  HttpResponseGeneratorBuffered(HttpResponse response, byte[][] data) {
    this.response = response;
    this.data = data;
  }

  @Override
  public HttpResponse getResponse() {
    return response;
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
}
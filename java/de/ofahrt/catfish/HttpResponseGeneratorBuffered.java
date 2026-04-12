package de.ofahrt.catfish;

import de.ofahrt.catfish.http.HttpResponseGenerator;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class HttpResponseGeneratorBuffered extends HttpResponseGenerator {
  public static HttpResponseGeneratorBuffered create(
      @Nullable HttpRequest request, HttpResponse response, boolean includeBody) {
    if (response.getBody() == null) {
      throw new IllegalArgumentException();
    }
    byte[] body = includeBody ? response.getBody() : EMPTY_BYTE_ARRAY;
    HttpHeaders headers = response.getHeaders();
    byte[][] data =
        new byte[][] {statusLineToByteArray(response), headersToByteArray(headers), body};
    return new HttpResponseGeneratorBuffered(request, response, data);
  }

  public static HttpResponseGeneratorBuffered createWithBody(
      @Nullable HttpRequest request, HttpResponse response) {
    return create(request, response, true);
  }

  private final @Nullable HttpRequest request;
  private final HttpResponse response;

  private final byte[][] data;
  private int currentBlock;
  private int currentIndex;

  HttpResponseGeneratorBuffered(
      @Nullable HttpRequest request, HttpResponse response, byte[][] data) {
    this.request = request;
    this.response = Objects.requireNonNull(response, "response");
    this.data = Objects.requireNonNull(data, "data");
  }

  @Override
  public @Nullable HttpRequest getRequest() {
    return request;
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
      int bytesCopyCount =
          Math.min(outputBuffer.remaining(), data[currentBlock].length - currentIndex);
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
  public void close() {}
}

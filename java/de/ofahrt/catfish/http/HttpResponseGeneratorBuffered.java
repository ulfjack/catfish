package de.ofahrt.catfish.http;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class HttpResponseGeneratorBuffered implements HttpResponseGenerator {
  public static HttpResponseGeneratorBuffered create(
      @Nullable HttpRequest request, HttpResponse response) {
    return create(request, response, /* includeBody= */ true);
  }

  public static HttpResponseGeneratorBuffered createForHead(
      @Nullable HttpRequest request, HttpResponse response) {
    return create(request, response, /* includeBody= */ false);
  }

  private static HttpResponseGeneratorBuffered create(
      @Nullable HttpRequest request, HttpResponse response, boolean includeBody) {
    if (response.getBody() == null) {
      throw new IllegalArgumentException();
    }
    byte[] body = includeBody ? response.getBody() : HttpEncoder.EMPTY_BYTE_ARRAY;
    byte[][] data = new byte[][] {HttpEncoder.responseHeadToByteArray(response), body};
    return new HttpResponseGeneratorBuffered(request, response, data);
  }

  private final @Nullable HttpRequest request;
  private final HttpResponse response;

  private final byte[][] data;
  private int currentBlock;
  private int currentIndex;
  private long bodyBytesSent;

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
      if (currentBlock == 1) {
        bodyBytesSent += bytesCopyCount;
      }
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
  public void abort() {
    currentBlock = data.length;
    currentIndex = 0;
  }

  @Override
  public long getBodyBytesSent() {
    return bodyBytesSent;
  }

  @Override
  public boolean keepAlive() {
    return HttpConnectionHeader.isKeepAlive(response.getHeaders());
  }
}

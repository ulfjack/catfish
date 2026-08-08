package de.ofahrt.catfish.http;

import java.io.IOException;
import java.io.OutputStream;
import org.jspecify.annotations.Nullable;

/**
 * An OutputStream filter that strips chunked transfer encoding framing and forwards only the
 * decoded body bytes to the wrapped stream. Used to capture decoded response bodies when the origin
 * sends chunked encoding.
 *
 * <p>A thin adapter over {@link ChunkedBodyState} (the single strict grammar): decoded content
 * spans are written straight to the delegate. Malformed framing raises {@link IOException}. Bytes
 * after the terminal chunk are ignored.
 */
public final class ChunkedDecodingOutputStream extends OutputStream {

  private final OutputStream delegate;
  private final ChunkedBodyState state = new ChunkedBodyState();
  private final DecodeSink sink = new DecodeSink();
  private final byte[] single = new byte[1];

  public ChunkedDecodingOutputStream(OutputStream delegate) {
    this.delegate = delegate;
  }

  @Override
  public void write(int b) throws IOException {
    single[0] = (byte) b;
    write(single, 0, 1);
  }

  @Override
  public void write(byte[] buf, int off, int len) throws IOException {
    state.advance(buf, off, len, sink);
    if (sink.failure != null) {
      IOException e = sink.failure;
      sink.failure = null;
      throw e;
    }
    if (state.hasError()) {
      throw new IOException("Malformed chunked body");
    }
  }

  @Override
  public void flush() throws IOException {
    delegate.flush();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  /** Forwards decoded spans to the delegate, stashing the first write failure to rethrow. */
  private final class DecodeSink implements ChunkedBodyState.Sink {
    private @Nullable IOException failure;

    @Override
    public void data(byte[] buf, int off, int len) {
      if (failure != null) {
        return;
      }
      try {
        delegate.write(buf, off, len);
      } catch (IOException e) {
        failure = e;
      }
    }
  }
}

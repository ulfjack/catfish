package de.ofahrt.catfish;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * A connected upstream origin: the input/output streams to speak to it plus the underlying resource
 * (a {@link java.net.Socket} or a {@link java.nio.channels.SocketChannel}) to close when done.
 * Produced by an {@link OriginDialer} so that {@link OriginForwarder} is transport-agnostic — it
 * pumps bytes over {@link #in()}/{@link #out()} without knowing whether the transport is TCP, TLS,
 * or a unix domain socket. {@link #close()} closes {@link #underlying()}.
 */
record OriginConnection(InputStream in, OutputStream out, Closeable underlying)
    implements Closeable {

  OriginConnection {
    Objects.requireNonNull(in, "in");
    Objects.requireNonNull(out, "out");
    Objects.requireNonNull(underlying, "underlying");
  }

  @Override
  public void close() throws IOException {
    underlying.close();
  }
}

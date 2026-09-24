package de.ofahrt.catfish.upload;

import de.ofahrt.catfish.model.HttpRequest;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A request body whose bytes live in a temporary file on disk rather than in the JVM heap, produced
 * by {@link BodySink} when a body exceeds its spill threshold.
 *
 * <p>{@link #openStream} performs blocking disk reads and must therefore only be called on the
 * application thread pool, never on a network (selector) thread.
 *
 * <p>The backing file is owned by the server, not by this object: whoever created the body is
 * responsible for calling {@link #release} exactly once when the request is finished (on every
 * completion and abort path). {@code release} is idempotent, so releasing more than once — or
 * before the file was ever written — is harmless.
 */
public final class FileBackedBody implements HttpRequest.Body {
  private final Path file;
  private final long length;

  public FileBackedBody(Path file, long length) {
    this.file = Objects.requireNonNull(file, "file");
    if (length < 0) {
      throw new IllegalArgumentException("length");
    }
    this.length = length;
  }

  /** The backing temporary file. Exposed so the owning server code can move/copy or delete it. */
  public Path getFile() {
    return file;
  }

  @Override
  public InputStream openStream() throws IOException {
    return new BufferedInputStream(Files.newInputStream(file));
  }

  @Override
  public long length() {
    return length;
  }

  /** Deletes the backing temporary file. Idempotent. */
  public void release() throws IOException {
    Files.deleteIfExists(file);
  }
}

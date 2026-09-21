package de.ofahrt.catfish.upload;

import de.ofahrt.catfish.model.HttpRequest;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Accumulates the bytes of a request body, keeping small bodies in memory and spilling large ones
 * to a temporary file on disk.
 *
 * <p>Bytes are buffered in memory until the total would exceed {@code spillThreshold}; at that
 * point a temporary file is created, the buffered bytes are flushed to it, and this and all
 * subsequent bytes are written to the file. {@link #finish} returns an {@link
 * HttpRequest.InMemoryBody} if the body stayed within the threshold, or a {@link FileBackedBody} if
 * it spilled.
 *
 * <p>A body exactly equal to {@code spillThreshold} stays in memory; only a body strictly larger
 * spills. A threshold of {@link Long#MAX_VALUE} never spills.
 *
 * <p>This class is single-threaded and does blocking file I/O in {@link #write}; callers running on
 * a network (selector) thread must offload it. If the sink is abandoned before {@link #finish} (an
 * aborted upload), {@link #close} deletes any temporary file that was created. After a successful
 * {@link #finish} that produced a {@link FileBackedBody}, ownership of the file transfers to that
 * body and {@link #close} leaves it in place.
 */
public final class BodySink implements Closeable {
  private final long spillThreshold;
  private final Path spillDirectory;

  private final ByteArrayOutputStream memory = new ByteArrayOutputStream();
  private long size;
  private @Nullable Path file;
  private @Nullable OutputStream fileOut;
  private boolean finished;

  /**
   * @param spillThreshold bodies larger than this many bytes spill to disk; {@link Long#MAX_VALUE}
   *     never spills
   * @param spillDirectory directory in which to create the temporary file
   */
  public BodySink(long spillThreshold, Path spillDirectory) {
    if (spillThreshold < 0) {
      throw new IllegalArgumentException("spillThreshold");
    }
    this.spillThreshold = spillThreshold;
    this.spillDirectory = Objects.requireNonNull(spillDirectory, "spillDirectory");
  }

  /** Appends {@code data[offset .. offset+length-1]} to the body, spilling to disk if needed. */
  public void write(byte[] data, int offset, int length) throws IOException {
    if (finished) {
      throw new IllegalStateException("write after finish");
    }
    if (length == 0) {
      return;
    }
    if (fileOut == null && size + length > spillThreshold) {
      spill();
    }
    OutputStream out = fileOut;
    if (out != null) {
      out.write(data, offset, length);
    } else {
      memory.write(data, offset, length);
    }
    size += length;
  }

  private void spill() throws IOException {
    Path f = createSpillFile(spillDirectory);
    OutputStream out = new BufferedOutputStream(Files.newOutputStream(f));
    try {
      memory.writeTo(out);
    } catch (IOException e) {
      closeQuietly(out);
      Files.deleteIfExists(f);
      throw e;
    }
    memory.reset();
    this.file = f;
    this.fileOut = out;
  }

  /**
   * Completes the body and returns it. After this call the sink must not be written to again; if a
   * temporary file was created, ownership transfers to the returned {@link FileBackedBody}.
   */
  public HttpRequest.Body finish() throws IOException {
    if (finished) {
      throw new IllegalStateException("already finished");
    }
    finished = true;
    OutputStream out = fileOut;
    Path f = file;
    if (out != null && f != null) {
      out.close();
      fileOut = null;
      return new FileBackedBody(f, size);
    }
    return new HttpRequest.InMemoryBody(memory.toByteArray());
  }

  /**
   * Aborts an unfinished sink, deleting any temporary file. A no-op for the file after a successful
   * {@link #finish} (the file belongs to the returned body).
   */
  @Override
  public void close() throws IOException {
    OutputStream out = fileOut;
    if (out != null) {
      fileOut = null;
      closeQuietly(out);
    }
    Path f = file;
    if (!finished && f != null) {
      Files.deleteIfExists(f);
      file = null;
    }
  }

  private static Path createSpillFile(Path dir) throws IOException {
    if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      FileAttribute<?> ownerOnly =
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
      return Files.createTempFile(dir, "catfish-upload-", ".tmp", ownerOnly);
    }
    return Files.createTempFile(dir, "catfish-upload-", ".tmp");
  }

  private static void closeQuietly(OutputStream out) {
    try {
      out.close();
    } catch (IOException ignored) {
      // Best-effort close on an error path.
    }
  }
}

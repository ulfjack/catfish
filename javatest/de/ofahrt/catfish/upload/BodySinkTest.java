package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.model.HttpRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BodySinkTest {

  private Path dir;

  @Before
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("bodysink-test-");
  }

  @After
  public void tearDown() throws IOException {
    if (dir != null && Files.exists(dir)) {
      try (Stream<Path> walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder()).forEach(BodySinkTest::deleteQuietly);
      }
    }
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException ignored) {
      // best effort
    }
  }

  private static byte[] bytes(int n) {
    byte[] b = new byte[n];
    for (int i = 0; i < n; i++) {
      b[i] = (byte) i;
    }
    return b;
  }

  private static byte[] read(HttpRequest.Body body) throws IOException {
    try (InputStream in = body.openStream()) {
      return in.readAllBytes();
    }
  }

  private List<Path> spillFiles() throws IOException {
    try (Stream<Path> list = Files.list(dir)) {
      return list.toList();
    }
  }

  @Test
  public void emptyBody_staysInMemory() throws IOException {
    HttpRequest.Body body;
    try (BodySink sink = new BodySink(100, dir)) {
      body = sink.finish();
    }
    assertTrue(body instanceof HttpRequest.InMemoryBody);
    assertEquals(0, body.length());
    assertArrayEquals(new byte[0], read(body));
    assertTrue(spillFiles().isEmpty());
  }

  @Test
  public void belowThreshold_staysInMemory() throws IOException {
    byte[] data = bytes(50);
    HttpRequest.Body body;
    try (BodySink sink = new BodySink(100, dir)) {
      sink.write(data, 0, data.length);
      body = sink.finish();
    }
    assertTrue(body instanceof HttpRequest.InMemoryBody);
    assertEquals(50, body.length());
    assertArrayEquals(data, read(body));
    assertTrue(spillFiles().isEmpty());
  }

  @Test
  public void exactlyThreshold_staysInMemory() throws IOException {
    byte[] data = bytes(10);
    HttpRequest.Body body;
    try (BodySink sink = new BodySink(10, dir)) {
      sink.write(data, 0, data.length);
      body = sink.finish();
    }
    assertTrue("body == threshold must stay in memory", body instanceof HttpRequest.InMemoryBody);
    assertTrue(spillFiles().isEmpty());
  }

  @Test
  public void aboveThreshold_spillsToFile() throws IOException {
    byte[] data = bytes(11);
    HttpRequest.Body body;
    try (BodySink sink = new BodySink(10, dir)) {
      sink.write(data, 0, data.length);
      body = sink.finish();
    }
    assertTrue(body instanceof FileBackedBody);
    assertEquals(11, body.length());
    assertArrayEquals(data, read(body));
    assertEquals(1, spillFiles().size());
    assertTrue(Files.exists(((FileBackedBody) body).getFile()));
  }

  @Test
  public void multipleWritesCrossingThreshold_concatenate() throws IOException {
    byte[] first = bytes(5);
    byte[] second = bytes(6);
    HttpRequest.Body body;
    try (BodySink sink = new BodySink(8, dir)) {
      sink.write(first, 0, first.length);
      sink.write(second, 0, second.length);
      body = sink.finish();
    }
    assertTrue(body instanceof FileBackedBody);
    byte[] expected = new byte[11];
    System.arraycopy(first, 0, expected, 0, 5);
    System.arraycopy(second, 0, expected, 5, 6);
    assertArrayEquals(expected, read(body));
  }

  @Test
  public void spillFile_isOwnerOnlyReadable() throws IOException {
    if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return; // permission model only asserted on POSIX filesystems
    }
    byte[] data = bytes(20);
    try (BodySink sink = new BodySink(1, dir)) {
      sink.write(data, 0, data.length);
      HttpRequest.Body body = sink.finish();
      Path file = ((FileBackedBody) body).getFile();
      assertEquals(
          PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }
  }

  @Test
  public void closeBeforeFinish_deletesTempFile() throws IOException {
    byte[] data = bytes(20);
    BodySink sink = new BodySink(1, dir);
    sink.write(data, 0, data.length);
    assertEquals(1, spillFiles().size());
    sink.close();
    assertTrue("aborted upload must leave no temp file", spillFiles().isEmpty());
  }

  @Test
  public void finishThenClose_leavesFileInPlace() throws IOException {
    byte[] data = bytes(20);
    FileBackedBody body;
    try (BodySink sink = new BodySink(1, dir)) {
      sink.write(data, 0, data.length);
      body = (FileBackedBody) sink.finish();
    }
    assertTrue(
        "finished body owns its file; close() must not delete it", Files.exists(body.getFile()));
    body.release();
    assertFalse(Files.exists(body.getFile()));
  }

  @Test
  public void release_isIdempotent() throws IOException {
    byte[] data = bytes(20);
    FileBackedBody body;
    try (BodySink sink = new BodySink(1, dir)) {
      sink.write(data, 0, data.length);
      body = (FileBackedBody) sink.finish();
    }
    body.release();
    body.release(); // second release must not throw
    assertFalse(Files.exists(body.getFile()));
  }

  @Test
  public void writeAfterFinish_throws() throws IOException {
    try (BodySink sink = new BodySink(100, dir)) {
      sink.finish();
      try {
        sink.write(bytes(1), 0, 1);
        fail("expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // ok
      }
    }
  }
}

package de.ofahrt.catfish.bridge;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public class FileDataTest {

  @Test
  public void getId() {
    FileData fd = new FileData("id42", "file.txt", new byte[0]);
    assertEquals("id42", fd.getId());
  }

  @Test
  public void getName() {
    FileData fd = new FileData("id1", "photo.jpg", new byte[0]);
    assertEquals("photo.jpg", fd.getName());
  }

  @Test
  public void getInputStreamReadsBytes() throws IOException {
    byte[] data = {10, 20, 30};
    FileData fd = new FileData("x", "f", data);
    try (InputStream is = fd.getInputStream()) {
      assertEquals(10, is.read());
      assertEquals(20, is.read());
      assertEquals(30, is.read());
      assertEquals(-1, is.read());
    }
  }

  @Test
  public void getInputStreamEmptyData() throws IOException {
    FileData fd = new FileData("x", "f", new byte[0]);
    try (InputStream is = fd.getInputStream()) {
      assertEquals(-1, is.read());
    }
  }
}

package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FormEntryTest {

  @Test
  public void fieldEntry() {
    FormEntry entry = new FormEntry("k", "v");
    assertEquals("k", entry.getName());
    assertEquals("v", entry.getValue());
    assertFalse(entry.isFile());
  }

  @Test
  public void fileEntry() {
    byte[] bytes = new byte[] {1, 2, 3};
    FormEntry entry = new FormEntry("f", "image/png", bytes);
    assertEquals("f", entry.getName());
    assertEquals("image/png", entry.getContentType());
    assertArrayEquals(bytes, entry.getBody());
    assertTrue(entry.isFile());
  }

  @Test(expected = NullPointerException.class)
  public void nullNameInFieldThrows() {
    new FormEntry(null, "v");
  }

  @Test(expected = NullPointerException.class)
  public void nullValueThrows() {
    new FormEntry("k", null);
  }

  @Test(expected = NullPointerException.class)
  public void nullNameInFileThrows() {
    new FormEntry(null, "image/png", new byte[0]);
  }

  @Test(expected = NullPointerException.class)
  public void nullContentTypeThrows() {
    new FormEntry("f", null, new byte[0]);
  }

  @Test(expected = NullPointerException.class)
  public void nullBodyThrows() {
    new FormEntry("f", "image/png", null);
  }
}

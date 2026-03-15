package de.ofahrt.catfish.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class MimeTypeRegistryTest {

  @Test
  public void knownExtension() {
    MimeType result = MimeTypeRegistry.guessFromExtension("html");
    assertEquals(MimeType.TEXT_HTML, result);
  }

  @Test
  public void unknownExtension() {
    MimeType result = MimeTypeRegistry.guessFromExtension("xyz123");
    assertSame(MimeTypeRegistry.DEFAULT_MIMETYPE, result);
  }

  @Test
  public void caseInsensitive() {
    MimeType lower = MimeTypeRegistry.guessFromExtension("html");
    MimeType upper = MimeTypeRegistry.guessFromExtension("HTML");
    assertEquals(lower, upper);
  }

  @Test
  public void guessFromFilenameKnown() {
    MimeType result = MimeTypeRegistry.guessFromFilename("page.html");
    assertEquals(MimeType.TEXT_HTML, result);
  }

  @Test
  public void guessFromFilenameUnknown() {
    MimeType result = MimeTypeRegistry.guessFromFilename("file.xyz123");
    assertSame(MimeTypeRegistry.DEFAULT_MIMETYPE, result);
  }
}

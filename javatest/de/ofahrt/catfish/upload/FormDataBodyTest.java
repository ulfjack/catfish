package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Map;
import org.junit.Test;

public class FormDataBodyTest {

  @Test
  public void emptyConstantSizeIsZero() {
    assertEquals(0, FormDataBody.EMPTY.size());
  }

  @Test
  public void emptyConstantIteratorHasNoElements() {
    assertFalse(FormDataBody.EMPTY.iterator().hasNext());
  }

  @Test
  public void sizeMatchesPartCount() {
    FormDataBody body =
        new FormDataBody(Arrays.asList(new FormEntry("a", "1"), new FormEntry("b", "2")));
    assertEquals(2, body.size());
  }

  @Test
  public void getReturnsCorrectEntry() {
    FormEntry entry = new FormEntry("k", "v");
    FormDataBody body = new FormDataBody(Arrays.asList(entry));
    assertSame(entry, body.get(0));
  }

  @Test
  public void iteratorCoversAllEntries() {
    FormDataBody body =
        new FormDataBody(Arrays.asList(new FormEntry("a", "1"), new FormEntry("b", "2")));
    int count = 0;
    for (FormEntry ignored : body) {
      count++;
    }
    assertEquals(2, count);
  }

  @Test
  public void formDataAsMapIncludesFieldEntries() {
    FormDataBody body =
        new FormDataBody(
            Arrays.asList(new FormEntry("name", "alice"), new FormEntry("age", "30")));
    Map<String, String> map = body.formDataAsMap();
    assertEquals("alice", map.get("name"));
    assertEquals("30", map.get("age"));
  }

  @Test
  public void formDataAsMapExcludesFileEntries() {
    FormDataBody body =
        new FormDataBody(
            Arrays.asList(
                new FormEntry("field", "value"),
                new FormEntry("upload", "image/png", new byte[] {1, 2, 3})));
    Map<String, String> map = body.formDataAsMap();
    assertEquals(1, map.size());
    assertEquals("value", map.get("field"));
  }

  @Test
  public void formDataAsMapEmptyWhenOnlyFiles() {
    FormDataBody body =
        new FormDataBody(
            Arrays.asList(new FormEntry("f", "image/png", new byte[] {0})));
    assertTrue(body.formDataAsMap().isEmpty());
  }
}

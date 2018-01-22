package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PathFragmentIteratorTest {

  @Test
  public void simple() {
    PathFragmentIterator tracker = new PathFragmentIterator("/a/b/c");
    assertTrue(tracker.hasNext());
    assertEquals("a", tracker.next());
    assertTrue(tracker.hasNext());
    assertEquals("b", tracker.next());
    assertEquals("/a/b/", tracker.getPath());
    assertEquals("c", tracker.getFilename());
  }
}

package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PathTrackerTest {

  @Test
  public void simple() {
    PathTracker tracker = new PathTracker("/a/b/c");
    assertTrue(tracker.hasNextPath());
    assertEquals("a", tracker.getNextPath());
    tracker.advance();
    assertTrue(tracker.hasNextPath());
    assertEquals("b", tracker.getNextPath());
  }
}

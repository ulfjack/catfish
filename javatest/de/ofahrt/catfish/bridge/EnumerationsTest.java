package de.ofahrt.catfish.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.junit.Test;

public class EnumerationsTest {

  @Test
  public void empty_hasNoElements() {
    assertFalse(Enumerations.empty().hasMoreElements());
  }

  @Test
  public void ofSingleValue_returnsElementOnce() {
    Enumeration<String> e = Enumerations.of("hello");
    assertTrue(e.hasMoreElements());
    assertEquals("hello", e.nextElement());
    assertFalse(e.hasMoreElements());
  }

  @Test
  public void ofSingleValue_secondCallThrows() {
    Enumeration<String> e = Enumerations.of("x");
    e.nextElement();
    assertThrows(NoSuchElementException.class, e::nextElement);
  }

  @Test
  public void ofIterator() {
    Enumeration<String> e = Enumerations.of(Arrays.asList("a", "b").iterator());
    assertEquals("a", e.nextElement());
    assertEquals("b", e.nextElement());
    assertFalse(e.hasMoreElements());
  }

  @Test
  public void ofIterable() {
    Enumeration<String> e = Enumerations.of(Arrays.asList("x", "y"));
    assertEquals("x", e.nextElement());
    assertEquals("y", e.nextElement());
  }

  @Test
  public void toString_multipleElements() {
    Enumeration<String> e = Enumerations.of(Arrays.asList("a", "b"));
    assertEquals("[a,b]", Enumerations.toString(e));
  }

  @Test
  public void toString_empty() {
    assertEquals("[]", Enumerations.toString(Enumerations.empty()));
  }

  @Test
  public void toArray_returnsAllElements() {
    Enumeration<String> e = Enumerations.of(Arrays.asList("x", "y"));
    String[] result = Enumerations.toArray(e, new String[0]);
    assertArrayEquals(new String[] {"x", "y"}, result);
  }

  @Test
  public void asIterator_remove_throws() {
    Iterator<String> it = Enumerations.asIterator(Enumerations.of("a"));
    assertThrows(UnsupportedOperationException.class, it::remove);
  }
}

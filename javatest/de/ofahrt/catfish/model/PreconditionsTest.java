package de.ofahrt.catfish.model;

import static org.junit.Assert.assertSame;

import org.junit.Test;

public class PreconditionsTest {

  @Test
  public void checkNotNullReturnsValue() {
    String value = "hello";
    assertSame(value, Preconditions.checkNotNull(value));
  }

  @Test(expected = NullPointerException.class)
  public void checkNotNullThrows() {
    Preconditions.checkNotNull(null);
  }

  @Test
  public void checkStateOk() {
    Preconditions.checkState(true);
  }

  @Test(expected = IllegalStateException.class)
  public void checkStateThrows() {
    Preconditions.checkState(false);
  }

  @Test
  public void checkArgumentOk() {
    Preconditions.checkArgument(true);
  }

  @Test(expected = IllegalArgumentException.class)
  public void checkArgumentThrows() {
    Preconditions.checkArgument(false);
  }
}

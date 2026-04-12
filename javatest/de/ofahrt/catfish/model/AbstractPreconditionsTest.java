package de.ofahrt.catfish.model;

import static org.junit.Assert.assertSame;

import org.junit.Test;

public abstract class AbstractPreconditionsTest {

  protected abstract Object checkNotNull(Object value);

  protected abstract void checkState(boolean expression);

  protected abstract void checkArgument(boolean expression);

  @Test
  public void checkNotNullReturnsValue() {
    String value = "hello";
    assertSame(value, checkNotNull(value));
  }

  @SuppressWarnings("NullAway") // intentionally passing null to test the null check
  @Test(expected = NullPointerException.class)
  public void checkNotNullThrows() {
    checkNotNull(null);
  }

  @Test
  public void checkStateOk() {
    checkState(true);
  }

  @Test(expected = IllegalStateException.class)
  public void checkStateThrows() {
    checkState(false);
  }

  @Test
  public void checkArgumentOk() {
    checkArgument(true);
  }

  @Test(expected = IllegalArgumentException.class)
  public void checkArgumentThrows() {
    checkArgument(false);
  }
}

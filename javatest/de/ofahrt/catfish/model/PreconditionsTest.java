package de.ofahrt.catfish.model;

public class PreconditionsTest extends AbstractPreconditionsTest {

  @Override
  protected Object checkNotNull(Object value) {
    return Preconditions.checkNotNull(value);
  }

  @Override
  protected void checkState(boolean expression) {
    Preconditions.checkState(expression);
  }

  @Override
  protected void checkArgument(boolean expression) {
    Preconditions.checkArgument(expression);
  }
}

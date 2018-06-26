package de.ofahrt.catfish.model;

final class Preconditions {

  public static <T> T checkNotNull(T value) {
    if (value == null) {
      throw new NullPointerException();
    }
    return value;
  }

  public static void checkState(boolean ok) {
    if (!ok) {
      throw new IllegalStateException();
    }
  }

  public static void checkArgument(boolean ok) {
    if (!ok) {
      throw new IllegalArgumentException();
    }
  }

  private Preconditions() {
    // Not instantiable.
  }
}

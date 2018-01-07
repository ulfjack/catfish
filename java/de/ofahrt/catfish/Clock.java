package de.ofahrt.catfish;

interface Clock {
  public static final class SystemClock implements Clock {
    @Override
    public long currentTimeMillis() {
      return System.currentTimeMillis();
    }
  }

  long currentTimeMillis();
}
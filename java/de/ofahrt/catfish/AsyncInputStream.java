package de.ofahrt.catfish;

interface AsyncInputStream {
  /**
   * If the return value is positive (> 0), then the buffer contains that many bytes of data. If
   * the return value is zero (== 0), then no data is currently available, but more data may be
   * available in the future. If the return value is negative (< 0), then the stream has no more
   * data available.
   */
  int readAsync(byte[] buffer, int off, int len);
}

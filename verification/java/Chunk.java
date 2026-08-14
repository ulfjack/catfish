/** RFC 9112 section 7.1 chunk-size line: 1*HEXDIG, rejecting overflow. */
public final class Chunk {

  public static int parseHexSize(byte[] b, int off, int len) {
    Verify.requires(
        """
        0 ≤ off ∧ 0 ≤ len ∧ off + len ≤ alen ∧ alen ≤ MAXI
        """);
    int acc = 0;
    int i = 0;
    for (; ; ) {
      Verify.invariant(
          """
          0 ≤ off ∧ 0 ≤ len ∧ off + len ≤ alen ∧ alen ≤ MAXI ∧
          0 ≤ i ∧ i ≤ len ∧ 0 ≤ acc ∧ acc ≤ MAXI ∧
          valOf b off.toNat i.toNat = some acc
          """);
      if (i >= len) break;
      int d = hexVal(b[off + i]);
      if (d < 0) {
        int r = -1;
        Verify.ensure("ret = -1");
        return r;
      }
      if (acc > (2147483647 - d) / 16) {
        int r = -1;
        Verify.ensure("ret = -1");
        return r;
      }
      acc = acc * 16 + d;
      i = i + 1;
    }
    int r = acc;
    Verify.ensure(
        """
        valOf b off.toNat len.toNat = some ret
        """);
    return r;
  }

  /** Verified separately against `digitVal`. */
  public static int hexVal(byte c) {
    if (c >= 48 && c <= 57) return c - 48;
    if (c >= 97 && c <= 102) return c - 87;
    if (c >= 65 && c <= 70) return c - 55;
    return -1;
  }
}

/** RFC 9112 section 7.1 chunk-size line: 1*HEXDIG, rejecting overflow. */
@ImportLeanPackage("ChunkedEncoding")
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

  /**
   * Value of a HEXDIG byte (RFC 9112), -1 otherwise. The @Returns contract is proved for every
   * return, and is what parseHexSize substitutes for the call (replacing calls.map).
   */
  @Returns("hexValF c")
  public static int hexVal(byte c) {
    Verify.requires("True");
    if (c >= 48 && c <= 57) {
      int r = c - 48;
      return r;
    }
    if (c >= 97 && c <= 102) {
      int r = c - 87;
      return r;
    }
    if (c >= 65 && c <= 70) {
      int r = c - 55;
      return r;
    }
    int r = -1;
    return r;
  }
}

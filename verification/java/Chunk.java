/** RFC 9112 section 7.1 chunk-size line: 1*HEXDIG, rejecting overflow. */
@ImportLeanPackage("ChunkedEncoding")
public final class Chunk {

  @Precondition("0 ≤ off ∧ 0 ≤ len ∧ off + len ≤ b.length ∧ b.length ≤ MAXI")
  @Returns("parseSpec b off.toNat len.toNat")
  public static int parseHexSize(byte[] b, int off, int len) {
    int acc = 0;
    int i = 0;
    int result;
    for (; ; ) {
      Verify.invariant(
          """
          0 ≤ off ∧ 0 ≤ len ∧ off + len ≤ b.length ∧ b.length ≤ MAXI ∧
          0 ≤ i ∧ i ≤ len ∧ 0 ≤ acc ∧ acc ≤ MAXI ∧
          valOf b off.toNat i.toNat = some acc
          """);
      if (i >= len) {
        result = (len == 0) ? -1 : acc; // RFC 1*HEXDIG: an empty field is invalid
        break;
      }
      int d = hexVal(b[off + i]);
      if (d < 0) {
        result = -1;
        break;
      }
      if (acc > (2147483647 - d) / 16) {
        result = -1;
        break;
      }
      acc = acc * 16 + d;
      i = i + 1;
    }
    return result;
  }

  /**
   * Value of a HEXDIG byte (RFC 9112), -1 otherwise. The @Returns contract is proved for every
   * return, and is what parseHexSize substitutes for the call (replacing calls.map).
   */
  @Returns("hexValF c")
  public static int hexVal(byte c) {
    int r;
    if (c >= 48 && c <= 57) {
      r = c - 48;
    } else if (c >= 97 && c <= 102) {
      r = c - 87;
    } else if (c >= 65 && c <= 70) {
      r = c - 55;
    } else {
      r = -1;
    }
    return r;
  }
}

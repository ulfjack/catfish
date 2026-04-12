package de.ofahrt.catfish.http2;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal HPACK header block encoder (RFC 7541). Uses static table lookups and
 * literal-without-indexing. No dynamic table, no Huffman encoding. Produces slightly larger output
 * than an optimized encoder but is correct and simple.
 */
public final class HpackEncoder {

  /** Encodes a list of headers into an HPACK header block. Thread-safe (no mutable state). */
  public byte[] encode(Hpack.Header... headers) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(256);
    byte[] buf = new byte[16]; // scratch for integer encoding

    for (Hpack.Header header : headers) {
      int exactIndex = HpackStaticTable.findExact(header.name(), header.value());
      if (exactIndex >= 0) {
        // Indexed header field (§6.1): emit index with 7-bit prefix, top bit = 1.
        int n = Hpack.encodeInteger(buf, 0, exactIndex, 7, 0x80);
        out.write(buf, 0, n);
        continue;
      }

      int nameIndex = HpackStaticTable.findName(header.name());
      if (nameIndex >= 0) {
        // Literal without indexing (§6.2.2), name index: prefix = 0000, 4-bit index.
        int n = Hpack.encodeInteger(buf, 0, nameIndex, 4, 0x00);
        out.write(buf, 0, n);
      } else {
        // Literal without indexing, new name: prefix byte = 0x00, then literal name.
        out.write(0x00);
        writeRawString(out, header.name(), buf);
      }
      writeRawString(out, header.value(), buf);
    }

    return out.toByteArray();
  }

  private void writeRawString(ByteArrayOutputStream out, String s, byte[] buf) {
    byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
    // String without Huffman: top bit = 0, 7-bit prefix length.
    int n = Hpack.encodeInteger(buf, 0, bytes.length, 7, 0x00);
    out.write(buf, 0, n);
    out.write(bytes, 0, bytes.length);
  }
}

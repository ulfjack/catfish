package de.ofahrt.catfish.http2;

import de.ofahrt.catfish.http2.Hpack.Header;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HPACK header block decoder (RFC 7541). Maintains a dynamic table across header blocks on the same
 * connection.
 */
final class HpackDecoder {

  private static final int DYNAMIC_TABLE_OVERHEAD = 32; // per RFC 7541 §4.1

  private final List<Header> dynamicTable = new ArrayList<>();
  private int dynamicTableSize;
  private int maxDynamicTableSize = 4096; // SETTINGS_HEADER_TABLE_SIZE default

  void setMaxDynamicTableSize(int max) {
    this.maxDynamicTableSize = max;
    evict();
  }

  /**
   * Decodes a complete header block into a list of headers. The block must be a complete HEADERS
   * frame payload (CONTINUATION not supported).
   */
  List<Header> decode(byte[] data, int offset, int length) throws HpackDecodingException {
    List<Header> headers = new ArrayList<>();
    int end = offset + length;
    int[] pos = new int[1];

    while (offset < end) {
      int firstByte = data[offset] & 0xff;

      if ((firstByte & 0x80) != 0) {
        // Indexed header field (§6.1): top bit = 1
        int index = Hpack.decodeInteger(data, offset, end - offset, 7, pos);
        if (index < 0) {
          throw new HpackDecodingException("Truncated indexed header");
        }
        offset = pos[0];
        headers.add(getIndexed(index));

      } else if ((firstByte & 0xc0) == 0x40) {
        // Literal with incremental indexing (§6.2.1): top bits = 01
        offset = decodeLiteral(data, offset, end, 6, headers, true);

      } else if ((firstByte & 0xf0) == 0x00) {
        // Literal without indexing (§6.2.2): top bits = 0000
        offset = decodeLiteral(data, offset, end, 4, headers, false);

      } else if ((firstByte & 0xf0) == 0x10) {
        // Literal never indexed (§6.2.3): top bits = 0001
        offset = decodeLiteral(data, offset, end, 4, headers, false);

      } else if ((firstByte & 0xe0) == 0x20) {
        // Dynamic table size update (§6.3): top bits = 001
        int newSize = Hpack.decodeInteger(data, offset, end - offset, 5, pos);
        if (newSize < 0) {
          throw new HpackDecodingException("Truncated table size update");
        }
        offset = pos[0];
        if (newSize > maxDynamicTableSize) {
          throw new HpackDecodingException("Dynamic table size exceeds maximum");
        }
        maxDynamicTableSize = newSize;
        evict();

      } else {
        throw new HpackDecodingException(
            "Unknown HPACK encoding prefix: 0x" + Integer.toHexString(firstByte));
      }
    }

    return headers;
  }

  private int decodeLiteral(
      byte[] data, int offset, int end, int prefixBits, List<Header> headers, boolean addToTable)
      throws HpackDecodingException {
    int[] pos = new int[1];
    int nameIndex = Hpack.decodeInteger(data, offset, end - offset, prefixBits, pos);
    if (nameIndex < 0) {
      throw new HpackDecodingException("Truncated literal header name index");
    }
    offset = pos[0];

    String name;
    if (nameIndex == 0) {
      // Name is a literal string.
      name = decodeString(data, offset, end, pos);
      offset = pos[0];
    } else {
      name = getIndexed(nameIndex).name();
    }

    String value = decodeString(data, offset, end, pos);
    offset = pos[0];

    Header header = new Header(name, value);
    headers.add(header);

    if (addToTable) {
      addToDynamicTable(header);
    }

    return offset;
  }

  private String decodeString(byte[] data, int offset, int end, int[] pos)
      throws HpackDecodingException {
    if (offset >= end) {
      throw new HpackDecodingException("Truncated string");
    }
    boolean huffman = (data[offset] & 0x80) != 0;
    int strLen = Hpack.decodeInteger(data, offset, end - offset, 7, pos);
    if (strLen < 0) {
      throw new HpackDecodingException("Truncated string length");
    }
    offset = pos[0];
    if (offset + strLen > end) {
      throw new HpackDecodingException("String extends past block end");
    }

    String result;
    if (huffman) {
      result = HpackHuffman.decode(data, offset, strLen);
    } else {
      result = new String(data, offset, strLen, StandardCharsets.ISO_8859_1);
    }
    pos[0] = offset + strLen;
    return result;
  }

  private Header getIndexed(int index) throws HpackDecodingException {
    if (index <= 0) {
      throw new HpackDecodingException("Invalid index: " + index);
    }
    if (index <= HpackStaticTable.SIZE) {
      return HpackStaticTable.get(index);
    }
    int dynamicIndex = index - HpackStaticTable.SIZE - 1;
    if (dynamicIndex >= dynamicTable.size()) {
      throw new HpackDecodingException(
          "Dynamic table index out of range: "
              + index
              + " (dynamic size="
              + dynamicTable.size()
              + ")");
    }
    return dynamicTable.get(dynamicIndex);
  }

  private void addToDynamicTable(Header header) {
    int entrySize = header.name().length() + header.value().length() + DYNAMIC_TABLE_OVERHEAD;
    dynamicTable.add(0, header);
    dynamicTableSize += entrySize;
    evict();
  }

  private void evict() {
    while (dynamicTableSize > maxDynamicTableSize && !dynamicTable.isEmpty()) {
      Header removed = dynamicTable.remove(dynamicTable.size() - 1);
      dynamicTableSize -=
          removed.name().length() + removed.value().length() + DYNAMIC_TABLE_OVERHEAD;
    }
  }

  /** Exception thrown when an HPACK block is malformed. */
  static final class HpackDecodingException extends Exception {
    HpackDecodingException(String message) {
      super(message);
    }
  }
}

package de.ofahrt.catfish.http2;

import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.http2.Hpack.Header;
import de.ofahrt.catfish.http2.HpackDecoder.HpackDecodingException;
import java.util.List;
import org.junit.Test;

public class HpackTest {

  // ---- Integer codec (RFC 7541 §C.1) ----

  @Test
  public void encodeInteger_fitsInPrefix() {
    byte[] buf = new byte[8];
    int n = Hpack.encodeInteger(buf, 0, 10, 5, 0x00);
    assertEquals(1, n);
    assertEquals(10, buf[0] & 0x1f);
  }

  @Test
  public void encodeInteger_exceedsPrefix() {
    // RFC 7541 §C.1.2: encoding 1337 with 5-bit prefix
    byte[] buf = new byte[8];
    int n = Hpack.encodeInteger(buf, 0, 1337, 5, 0x00);
    assertEquals(3, n);
    assertEquals(31, buf[0] & 0xff);
    assertEquals(154, buf[1] & 0xff);
    assertEquals(10, buf[2] & 0xff);
  }

  @Test
  public void decodeInteger_fitsInPrefix() {
    byte[] data = {10};
    int[] pos = new int[1];
    int val = Hpack.decodeInteger(data, 0, 1, 5, pos);
    assertEquals(10, val);
    assertEquals(1, pos[0]);
  }

  @Test
  public void decodeInteger_exceedsPrefix() {
    // 1337 encoded with 5-bit prefix: 31, 154, 10
    byte[] data = {31, (byte) 154, 10};
    int[] pos = new int[1];
    int val = Hpack.decodeInteger(data, 0, 3, 5, pos);
    assertEquals(1337, val);
    assertEquals(3, pos[0]);
  }

  @Test
  public void integerRoundTrip() {
    byte[] buf = new byte[16];
    for (int prefixBits = 1; prefixBits <= 8; prefixBits++) {
      for (int value : new int[] {0, 1, 30, 31, 127, 128, 255, 1337, 65535}) {
        int n = Hpack.encodeInteger(buf, 0, value, prefixBits, 0x00);
        int[] pos = new int[1];
        int decoded = Hpack.decodeInteger(buf, 0, n, prefixBits, pos);
        assertEquals("prefixBits=" + prefixBits + " value=" + value, value, decoded);
        assertEquals(n, pos[0]);
      }
    }
  }

  // ---- Decoder: RFC 7541 §C.2 (literal header field representations) ----

  @Test
  public void decode_literalWithIndexing_customKV() throws HpackDecodingException {
    // §C.2.1: custom-key: custom-header (literal with incremental indexing, new name)
    byte[] block = {
      0x40, // literal with indexing, new name
      0x0a, // name length = 10 (raw)
      'c', 'u', 's', 't', 'o', 'm', '-', 'k', 'e', 'y', 0x0d, // value length = 13 (raw)
      'c', 'u', 's', 't', 'o', 'm', '-', 'h', 'e', 'a', 'd', 'e', 'r'
    };
    HpackDecoder decoder = new HpackDecoder();
    List<Header> headers = decoder.decode(block, 0, block.length);
    assertEquals(1, headers.size());
    assertEquals("custom-key", headers.get(0).name());
    assertEquals("custom-header", headers.get(0).value());
  }

  @Test
  public void decode_literalWithoutIndexing() throws HpackDecodingException {
    // §C.2.2: literal without indexing, indexed name (":path" = index 4), value "/sample/path"
    byte[] block = {
      0x04, // literal without indexing, name index = 4 (:path)
      0x0c, // value length = 12 (raw)
      '/', 's', 'a', 'm', 'p', 'l', 'e', '/', 'p', 'a', 't', 'h'
    };
    HpackDecoder decoder = new HpackDecoder();
    List<Header> headers = decoder.decode(block, 0, block.length);
    assertEquals(1, headers.size());
    assertEquals(":path", headers.get(0).name());
    assertEquals("/sample/path", headers.get(0).value());
  }

  @Test
  public void decode_literalNeverIndexed() throws HpackDecodingException {
    // §C.2.3: literal never indexed, indexed name ("password" not in table → new name)
    byte[] block = {
      0x10, // literal never indexed, new name
      0x08, // name length = 8
      'p', 'a', 's', 's', 'w', 'o', 'r', 'd', 0x06, // value length = 6
      's', 'e', 'c', 'r', 'e', 't'
    };
    HpackDecoder decoder = new HpackDecoder();
    List<Header> headers = decoder.decode(block, 0, block.length);
    assertEquals(1, headers.size());
    assertEquals("password", headers.get(0).name());
    assertEquals("secret", headers.get(0).value());
  }

  @Test
  public void decode_indexed() throws HpackDecodingException {
    // Index 2 = ":method GET"
    byte[] block = {(byte) 0x82};
    HpackDecoder decoder = new HpackDecoder();
    List<Header> headers = decoder.decode(block, 0, block.length);
    assertEquals(1, headers.size());
    assertEquals(":method", headers.get(0).name());
    assertEquals("GET", headers.get(0).value());
  }

  @Test
  public void decode_dynamicTable() throws HpackDecodingException {
    HpackDecoder decoder = new HpackDecoder();
    // First block: add "custom-key: custom-value" to dynamic table via literal with indexing.
    byte[] block1 = {
      0x40, 0x0a, 'c', 'u', 's', 't', 'o', 'm', '-', 'k', 'e', 'y', 0x0c, 'c', 'u', 's', 't', 'o',
      'm', '-', 'v', 'a', 'l', 'u', 'e'
    };
    decoder.decode(block1, 0, block1.length);

    // Second block: reference dynamic table entry (index 62 = first dynamic entry).
    byte[] block2 = {(byte) 0xbe}; // indexed, index = 62
    List<Header> headers = decoder.decode(block2, 0, block2.length);
    assertEquals(1, headers.size());
    assertEquals("custom-key", headers.get(0).name());
    assertEquals("custom-value", headers.get(0).value());
  }

  // ---- Decoder: RFC 7541 §C.3 (request examples without Huffman) ----

  @Test
  public void decode_rfc7541_c3_firstRequest() throws HpackDecodingException {
    // §C.3.1: First request
    byte[] block = {
      (byte) 0x82, // :method GET (indexed 2)
      (byte) 0x86, // :scheme http (indexed 6)
      (byte) 0x84, // :path / (indexed 4)
      0x41,
      0x0f, // :authority (indexed name 1), value length 15
      'w',
      'w',
      'w',
      '.',
      'e',
      'x',
      'a',
      'm',
      'p',
      'l',
      'e',
      '.',
      'c',
      'o',
      'm'
    };
    HpackDecoder decoder = new HpackDecoder();
    List<Header> headers = decoder.decode(block, 0, block.length);
    assertEquals(4, headers.size());
    assertEquals(":method", headers.get(0).name());
    assertEquals("GET", headers.get(0).value());
    assertEquals(":scheme", headers.get(1).name());
    assertEquals("http", headers.get(1).value());
    assertEquals(":path", headers.get(2).name());
    assertEquals("/", headers.get(2).value());
    assertEquals(":authority", headers.get(3).name());
    assertEquals("www.example.com", headers.get(3).value());
  }

  // ---- Encoder ----

  @Test
  public void encode_indexedHeader() {
    HpackEncoder encoder = new HpackEncoder();
    byte[] block = encoder.encode(new Header(":method", "GET"));
    // Should emit indexed representation for static table entry 2.
    assertEquals(1, block.length);
    assertEquals((byte) 0x82, block[0]);
  }

  @Test
  public void encode_nameIndexedValueLiteral() {
    HpackEncoder encoder = new HpackEncoder();
    byte[] block = encoder.encode(new Header(":path", "/foo"));
    // :path is index 4 in static table. Literal without indexing, name index = 4.
    assertEquals(0x04, block[0] & 0xff); // prefix 0000, index 4
    assertEquals(4, block[1] & 0xff); // value length
    assertEquals('/', block[2]);
    assertEquals('f', block[3]);
  }

  @Test
  public void encode_fullyLiteral() {
    HpackEncoder encoder = new HpackEncoder();
    byte[] block = encoder.encode(new Header("x-custom", "val"));
    assertEquals(0x00, block[0] & 0xff); // literal without indexing, new name
    assertEquals(8, block[1] & 0xff); // name length
    // name bytes
    assertEquals('x', block[2]);
    assertEquals(14, block.length); // 1 + 1 + 8 + 1 + 3
  }

  @Test
  public void encodeDecode_roundTrip() throws HpackDecodingException {
    HpackEncoder encoder = new HpackEncoder();
    Header[] original = {
      new Header(":status", "200"),
      new Header("content-type", "text/html"),
      new Header("x-custom", "value123"),
    };
    byte[] block = encoder.encode(original);

    HpackDecoder decoder = new HpackDecoder();
    List<Header> decoded = decoder.decode(block, 0, block.length);
    assertEquals(3, decoded.size());
    for (int i = 0; i < original.length; i++) {
      assertEquals(original[i].name(), decoded.get(i).name());
      assertEquals(original[i].value(), decoded.get(i).value());
    }
  }

  // ---- Huffman ----

  @Test
  public void decode_huffmanString_noCache() {
    // "no-cache" Huffman-encoded from RFC 7541 §C.4.1
    byte[] huffBytes = {(byte) 0xa8, (byte) 0xeb, 0x10, 0x64, (byte) 0x9c, (byte) 0xbf};
    String decoded = HpackHuffman.decode(huffBytes, 0, huffBytes.length);
    assertEquals("no-cache", decoded);
  }

  @Test
  public void decode_huffmanEncodedHeaderBlock() throws HpackDecodingException {
    // RFC 7541 §C.4.1: First request (Huffman encoded)
    byte[] block = {
      (byte) 0x82, // :method GET (indexed 2)
      (byte) 0x86, // :scheme http (indexed 6)
      (byte) 0x84, // :path / (indexed 4)
      0x41, // :authority (literal with indexing, name index 1)
      (byte) 0x8c, // value length 12, Huffman flag
      (byte) 0xf1,
      (byte) 0xe3,
      (byte) 0xc2,
      (byte) 0xe5,
      (byte) 0xf2,
      0x3a,
      0x6b,
      (byte) 0xa0,
      (byte) 0xab,
      (byte) 0x90,
      (byte) 0xf4,
      (byte) 0xff
    };
    HpackDecoder decoder = new HpackDecoder();
    List<Header> headers = decoder.decode(block, 0, block.length);
    assertEquals(4, headers.size());
    assertEquals(":authority", headers.get(3).name());
    assertEquals("www.example.com", headers.get(3).value());
  }
}

package de.ofahrt.catfish.ssl;

import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Parser for ASN.1.
 */
final class Asn1Parser {
  private static final int INTEGER_TAG = 0x02;
  private static final int OCTET_STRING_TAG = 0x04;
  private static final int NULL_TAG = 0x05;
  private static final int OBJECT_IDENTIFIER_TAG = 0x06;
  private static final int SEQUENCE_TAG = 0x30;

  enum Event {
    INTEGER,
    OCTET_STRING,
    NULL,
    OBJECT_IDENTIFIER,
    UNKNOWN,
    SEQUENCE,
    END_SEQUENCE,
    END_INPUT;
  }

  static final class ObjectIdentifier {
    private final int[] segments;

    ObjectIdentifier(int[] segments) {
      this.segments = segments;
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(segments);
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      if (!(o instanceof ObjectIdentifier)) {
        return false;
      }
      ObjectIdentifier i = (ObjectIdentifier) o;
      return Arrays.equals(segments, i.segments);
    }

    @Override
    public String toString() {
      StringBuilder result = new StringBuilder();
      result.append(segments[0]);
      for (int i = 1; i < segments.length; i++) {
        result.append(".").append(segments[i]);
      }
      return result.toString();
    }
  }

  private static final class Section {
    private final Event endEvent;
    private final int endOfData;

    Section(Event endEvent, int endOfData) {
      this.endEvent = endEvent;
      this.endOfData = endOfData;
    }
  }

  private final byte[] data;
  private int index;
  private int endOfData;
  private Deque<Section> deque = new ArrayDeque<>();
  private Event currentTag;
  private Object currentObject;

  Asn1Parser(byte[] data) {
    this.data = data;
    this.endOfData = data.length;
  }

  private void checkLength(int len) throws IOException {
    if (index + len > endOfData) {
      throw new EOFException("Unexpected length");
    }
  }

  private int readByte() throws IOException {
    checkLength(1);
    return data[index++] & 0xff;
  }

  private void readFully(byte[] buffer) throws IOException {
    checkLength(buffer.length);
    System.arraycopy(data, index, buffer, 0, buffer.length);
    index += buffer.length;
  }

  public Event nextEvent() throws IOException {
    if (index == endOfData) {
      if (deque.isEmpty()) {
        currentTag = Event.END_INPUT;
      } else {
        Section section = deque.pop();
        currentTag = section.endEvent;
        endOfData = section.endOfData;
      }
      return currentTag;
    }

    int tag = readByte();
    int length = readEncodedLength();
    checkLength(length);
    switch (tag) {
      case INTEGER_TAG:
        currentTag = Event.INTEGER;
        currentObject = readBigInteger(length);
        break;
      case OCTET_STRING_TAG:
        currentTag = Event.OCTET_STRING;
        currentObject = readOctetString(length);
        break;
      case NULL_TAG:
        currentTag = Event.NULL;
        if (length != 0) {
          throw new IOException("NULL element with non-zero length: " + length);
        }
        break;
      case OBJECT_IDENTIFIER_TAG:
        currentTag = Event.OBJECT_IDENTIFIER;
        currentObject = readObjectIdentifier(length);
        break;
      case SEQUENCE_TAG:
        currentTag = Event.SEQUENCE;
        deque.push(new Section(Event.END_SEQUENCE, endOfData));
        endOfData = index + length;
        break;
      default:
        index += length;
        throw new IOException("Unknown tag type: " + Integer.toHexString(tag));
    }
    return currentTag;
  }

  public Event tag() {
    return currentTag;
  }

  public BigInteger getInteger() {
    if (currentTag != Event.INTEGER) {
      throw new IllegalStateException();
    }
    return (BigInteger) currentObject;
  }

  public byte[] getOctetString() {
    if (currentTag != Event.OCTET_STRING) {
      throw new IllegalStateException();
    }
    return (byte[]) currentObject;
  }

  public ObjectIdentifier getObjectIdentifier() {
    if (currentTag != Event.OBJECT_IDENTIFIER) {
      throw new IllegalStateException();
    }
    return (ObjectIdentifier) currentObject;
  }

  private int readEncodedLength() throws IOException {
    int value = readByte();
    if ((value & 0x80) != 0) {
      return readEncodedLength(value & 0x7f);
    } else {
      return value;
    }
  }

  private int readEncodedLength(int len) throws IOException {
    if (len > 4) {
      throw new IOException("Unexpectedly long length");
    }
    int value = 0;
    for (int i = 0; i < len; i++) {
      value = (value << 8) | readByte();
    }
    return value;
  }

  private BigInteger readBigInteger(int length) throws IOException {
    byte[] temp = new byte[length];
    readFully(temp);
    return new BigInteger(temp);
  }

  private byte[] readOctetString(int length) throws IOException {
    byte[] temp = new byte[length];
    readFully(temp);
    return temp;
  }

  private ObjectIdentifier readObjectIdentifier(int length) throws IOException {
    int[] segments = new int[10];
    int count = 2;
    int start = index;
    int firstPair = readByte();
    segments[0] = firstPair / 40;
    segments[1] = firstPair % 40;
    while (index - start < length) {
      int value = 0;
      int b;
      do {
        b = readByte();
        value = value * 128 + (b & 0x7f);
      } while ((b & 0x80) == 0x80);
      segments[count++] = value;
    }
    return new ObjectIdentifier(Arrays.copyOf(segments, count));
  }

  public void parse() throws IOException {
    int indentation = 0;
    Event e;
    while ((e = nextEvent()) != Event.END_INPUT) {
      switch (e) {
        case INTEGER:
          System.out.println(indent(indentation) + e + " -> " + getInteger());
          break;
        case OCTET_STRING:
          System.out.println(indent(indentation) + e + " -> " + Arrays.toString(getOctetString()));
          break;
        case OBJECT_IDENTIFIER:
          System.out.println(indent(indentation) + e + " -> " + getObjectIdentifier());
          break;
        case SEQUENCE:
          System.out.println(indent(indentation) + e);
          indentation += 2;
          break;
        case END_SEQUENCE:
          indentation -= 2;
          System.out.println(indent(indentation) + e);
          break;
        default:
          System.out.println(indent(indentation) + e);
          break;
      }
    }
  }

  private String indent(int indentation) {
    StringBuilder builder = new StringBuilder(indentation);
    for (int i = 0; i < indentation; i++) {
      builder.append(' ');
    }
    return builder.toString();
  }
}

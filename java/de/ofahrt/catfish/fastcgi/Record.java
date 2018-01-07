package de.ofahrt.catfish.fastcgi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Map;

final class Record {

  private static final byte[] EMPTY_DATA = new byte[0];

  private byte version = (byte) FastCgiConstants.FCGI_VERSION_1;
  private byte type;
  private byte requestIdB1;
  private byte requestIdB0;
  private byte contentLengthB1;
  private byte contentLengthB0;
  private byte paddingLength;
  private byte reserved;
  private byte[] contentData = EMPTY_DATA;
  private byte[] paddingData = EMPTY_DATA;

  public Record() {
  }

  public int getType() {
    return type & 0xff;
  }

  public byte[] getContent() {
    return contentData;
  }

  public Record setType(int type) {
    this.type = (byte) type;
    return this;
  }

  public Record setRequestId(int requestId) {
    this.requestIdB0 = (byte) requestId;
    this.requestIdB1 = (byte) (requestId >>> 8);
    return this;
  }

  public Record setContent(byte[] data) {
    this.contentData = data;
    this.contentLengthB0 = (byte) data.length;
    this.contentLengthB1 = (byte) (data.length >>> 8);
    return this;
  }

  public Record setContentAsKeys(String... keys) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < keys.length; i++) {
      byte[] key = keys[i].getBytes(Charset.forName("UTF-8"));
      if (key.length > 127) {
        throw new IllegalArgumentException();
      }
      out.write(key.length);
      out.write(0);
      out.write(key, 0, key.length);
    }
    return setContent(out.toByteArray());
  }

  public Record setContentAsMap(Map<String, String> map) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Map.Entry<String, String> entry : map.entrySet()) {
      byte[] key = entry.getKey().getBytes(Charset.forName("UTF-8"));
      String valueString = entry.getValue() != null ? entry.getValue() : "";
      byte[] value = valueString.getBytes(Charset.forName("UTF-8"));
      if (key.length > 127) {
        throw new IllegalArgumentException();
      }
      if (value.length > 127) {
        throw new IllegalArgumentException();
      }
      out.write(key.length);
      out.write(value.length);
      out.write(key, 0, key.length);
      out.write(value, 0, value.length);
    }
    return setContent(out.toByteArray());
  }

  @Override
  public String toString() {
    StringBuilder result = new StringBuilder();
    result.append("VERSION=").append(version).append('\n');
    result.append("TYPE=").append(type).append('\n');
    int requestId = ((requestIdB1 & 0xff) << 8) | (requestIdB0 & 0xff);
    result.append("REQUEST_ID=").append(requestId).append('\n');
    int contentLength = ((contentLengthB1 & 0xff) << 8) | (contentLengthB0 & 0xff);
    result.append("CONTENT_LENGTH=").append(contentLength).append('\n');
    if (type == FastCgiConstants.FCGI_GET_VALUES_RESULT) {
      int i = 0;
      while (i < contentData.length) {
        int keyLength = contentData[i++];
        int valueLength = contentData[i++];
        String key = new String(contentData, i, keyLength);
        i += keyLength;
        String value = new String(contentData, i, valueLength);
        i += valueLength;
        result.append("  ").append(key).append("=").append(value).append('\n');
      }
    } else if (type == FastCgiConstants.FCGI_STDOUT) {
      for (byte b : contentData) {
        result.append((char) (b & 0xff));
      }
    }
    return result.toString();
  }

  public void writeTo(OutputStream out) throws IOException {
    byte[] temp = new byte[8];
    temp[0] = version;
    temp[1] = type;
    temp[2] = requestIdB1;
    temp[3] = requestIdB0;
    temp[4] = contentLengthB1;
    temp[5] = contentLengthB0;
    temp[6] = paddingLength;
    temp[7] = reserved;
    out.write(temp);
    out.write(contentData);
    out.write(paddingData);
  }

  public void readFrom(InputStream in) throws IOException {
    byte[] temp = new byte[8];
    int len = in.read(temp, 0, 8);
    if (len != 8) {
      throw new IOException("Argh: " + len);
    }
    version = temp[0];
    type = temp[1];
    requestIdB1 = temp[2];
    requestIdB0 = temp[3];
    contentLengthB1 = temp[4];
    contentLengthB0 = temp[5];
    paddingLength = temp[6];
    reserved = temp[7];
    contentData = new byte[(contentLengthB1 & 0xff) << 8 | (contentLengthB0 & 0xff)];
    int readBytes = 0;
    while (readBytes < contentData.length) {
      len = in.read(contentData, readBytes, contentData.length - readBytes);
      readBytes += len;
    }
    readBytes = 0;
    while (readBytes < paddingLength) {
      len = in.read(temp, 0, Math.min(paddingLength, 8));
      readBytes += len;
    }
  }
}

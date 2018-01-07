package de.ofahrt.catfish;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * {@link https://tools.ietf.org/rfc/rfc5246.txt}
 * {@link https://tools.ietf.org/rfc/rfc6066.txt}
 */
final class SNIParser {
  private static final boolean DEBUG = false;

  private static final int RECORD_HEADER_SIZE = 5;
  private static final int HANDSHAKE_RECORD_ID = 22;
  private static final int CLIENT_HELLO_ID = 1;
  private static final int CLIENT_HELLO_HEADER_SIZE = 4;
  // type + length + version + random + array(8) + array(16) + array(8)
  private static final int MIN_HANDSHAKE_RECORD_SIZE = 1 + 3 + 2 + 32 + 1 + 2 + 1;

  private static final int SNI_EXTENSION_ID = 0;
  private static final int SNI_DNS_HOSTNAME_ID = 0;

  private static final Result NOT_DONE = new Result(false, false);
  private static final Result NO_SNI_FOUND = new Result(true, false);
  private static final Result UNSUPPORTED_SPLIT_RECORD = new Result(true, true);
  private static final Result PARSE_ERROR = new Result(true, true);

  Result parse(ByteBuffer inputBuffer) {
    if (inputBuffer.remaining() == 0) {
      return NOT_DONE;
    }

    // We use a temporary byte buffer that points to the same content, but has independent
    // position, limit, and mark settings.
    ByteBuffer temp = inputBuffer.slice();
    int recordType = getInt8(temp);
    if (recordType != HANDSHAKE_RECORD_ID) {
      log("No SNI found (not a TLS handshake record)");
      return NO_SNI_FOUND;
    }
    if (inputBuffer.remaining() < RECORD_HEADER_SIZE) {
      return NOT_DONE;
    }

    int majorVersion = getInt8(temp); // major version
    int minorVersion = getInt8(temp); // minor version
    log("Version " + majorVersion + "." + minorVersion);

    int recordLength = getInt16(temp);
    if (temp.remaining() < recordLength) {
      // Not enough data for the entire record.
      log("Not enough data yet");
      return NOT_DONE;
    }
    if (recordLength < MIN_HANDSHAKE_RECORD_SIZE) {
      log("Not enough data for a minimal, complete ClientHello handshake record");
      return PARSE_ERROR;
    }
    // Safety mechanism - can't read beyond this one record.
    temp.limit(RECORD_HEADER_SIZE + recordLength);

    log("Enough data for initial record (" + recordLength + ")!");

    // Parse the Handshake struct (rfc 5246, p.36)
    byte handshakeType = temp.get();
    if (handshakeType != CLIENT_HELLO_ID) {
      log("No SNI found (not a TLS client hello record)");
      return NO_SNI_FOUND;
    }

    int handshakeLength = getInt24(temp);
    if (handshakeLength > (recordLength - CLIENT_HELLO_HEADER_SIZE)) {
      log("No SNI found (initial handshake appears split because " + handshakeLength + " > "
          + (recordLength - CLIENT_HELLO_HEADER_SIZE) + "; unsupported)");
      return UNSUPPORTED_SPLIT_RECORD;
    }
    // TODO: The handshakeLength could be shorter than the record length, and we should guard
    // against reading data in the record, but after the handshake.

    int helloMajorVersion = getInt8(temp);
    int helloMinorVersion = getInt8(temp);
    log("Hello version " + helloMajorVersion + "." + helloMinorVersion);

    if (!skip(temp, 32)) { // Skip random
      // This can never fail because we check that the packet has at least MIN_HANDSHAKE_RECORD_SIZE
      // bytes, which includes the fixed-length random field. The other ones below _can_ fail, as
      // they are variable-length fields (they read the length from the data, which can be
      // inconsistent with the total handshake length).
      return PARSE_ERROR;
    }
    if (!skip(temp, getInt8(temp))) { // Skip session_id
      return PARSE_ERROR;
    }
    if (!skip(temp, getInt16(temp))) { // Skip cipher_suites
      return PARSE_ERROR;
    }
    if (!skip(temp, getInt8(temp))) { // Skip compression_methods
      return PARSE_ERROR;
    }
    if (temp.remaining() == 0) {
      log("No SNI found (no extensions in handshake)");
      return NO_SNI_FOUND;
    }

    log("Found extensions, parsing...");
    int length = getInt16(temp);
    if (length > temp.remaining()) {
      return PARSE_ERROR;
    }
    while (length > 0) {
      int extensionType = getInt16(temp);
      int extensionLength = getInt16(temp);
      length -= 4;
      length -= extensionLength;
      if (length < 0) {
        log("Malformed packet: extension longer than expected");
        return PARSE_ERROR;
      }
      if (extensionType == SNI_EXTENSION_ID) {
        temp = temp.slice();
        temp.limit(extensionLength);
        return parseSNIExtension(temp);
      } else {
        skip(temp, extensionLength);
      }
    }
    return NO_SNI_FOUND;
  }

  private Result parseSNIExtension(ByteBuffer temp) {
    int listLength = getInt16(temp);
    if (listLength > temp.remaining()) {
      return PARSE_ERROR;
    }
    temp = temp.slice();
    temp.limit(listLength);
    String serverName = null;
    while (temp.remaining() > 0) {
      int code = getInt8(temp);
      int nameLength = getInt16(temp);
      if ((nameLength == 0) || (nameLength > temp.remaining())) {
        return PARSE_ERROR;
      }
      byte[] encoded = new byte[nameLength];
      temp.get(encoded);
      String name = new String(encoded, StandardCharsets.US_ASCII);
      if (code == SNI_DNS_HOSTNAME_ID) {
        // rfc 6066, p. 5 disallows two entries with the same code; we only check for the DNS host
        // name entry that we're actually interested in.
        if (serverName != null) {
          return PARSE_ERROR;
        }
        serverName = name;
      }
    }
    return new Result(serverName);
  }

  /** Skips as many bytes as given; returns false if there was an error. */
  private boolean skip(ByteBuffer temp, int length) {
    if (length > temp.remaining()) {
      return false;
    }
    if (length != 0) {
      int position = temp.position();
      temp.position(position + length);
    }
    return true;
  }

  private int getInt8(ByteBuffer temp) {
    return temp.get() & 0xff;
  }

  private int getInt16(ByteBuffer temp) {
    return ((temp.get() & 0xff) << 8) | (temp.get() & 0xff);
  }

  private int getInt24(ByteBuffer temp) {
    return ((temp.get() & 0xff) << 16) | ((temp.get() & 0xff) << 8) | (temp.get() & 0xff);
  }

  private void log(String text) {
    if (DEBUG) System.out.println(text);
  }

  public static final class Result {
    private final boolean done;
    private final boolean error;
    private final String name;

    public Result(String name) {
      this.done = true;
      this.error = false;
      this.name = name;
    }

    public Result(boolean done, boolean error) {
      if (!done && error) {
        throw new IllegalArgumentException();
      }
      this.done = done;
      this.error = error;
      this.name = null;
    }

    public boolean isDone() {
      return done;
    }

    public boolean hasError() {
      return error;
    }

    public String getName() {
      return name;
    }
  }
}

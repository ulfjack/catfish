package de.ofahrt.catfish;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Incremental HTTP/1.1 request header parser. Parses the request line and headers only; body
 * parsing is the caller's responsibility. After {@link #isDone()} returns true, call {@link
 * #getRequest()} to get the headers-only request.
 */
final class IncrementalHttpRequestParser {
  private static final int MAX_URI_LENGTH = 10_000;
  private static final int MAX_HEADER_NAME_LENGTH = 1000;
  private static final int MAX_HEADER_VALUE_LENGTH = 10_000;
  private static final int MAX_HEADER_FIELD_COUNT = 1000;

  private static final int TOKEN = 1;
  private static final int DIGIT = 2;
  private static final int SPACE = 4;
  private static final int CONTROL = 8;

  private static final byte[] CHAR_FLAGS = new byte[256];

  private static boolean isControlOrSeparator(int c) {
    return c < 32 || c == 127 || (c < 128 && "\"(),/:;<=>?@[\\]{} \t".indexOf(c) >= 0);
  }

  static {
    for (int c = 0; c < 256; c++) {
      int flags = 0;
      flags |= (c < 32 || c == 127) ? CONTROL : 0;
      flags |= (c == ' ' || c == '\t') ? SPACE : 0;
      flags |= (c >= '0' && c <= '9') ? DIGIT : 0;
      flags |= (!isControlOrSeparator(c)) ? TOKEN : 0;
      CHAR_FLAGS[c] = (byte) flags;
    }
  }

  //       CTL            = <any US-ASCII control character
  //                        (octets 0 - 31) and DEL (127)>
  private static boolean isControl(char c) {
    return (CHAR_FLAGS[c] & CONTROL) != 0;
  }

  static boolean isTokenCharacter(char c) {
    return (CHAR_FLAGS[c] & TOKEN) != 0;
  }

  private static boolean isDigit(char c) {
    return (CHAR_FLAGS[c] & DIGIT) != 0;
  }

  private static boolean isSpace(char c) {
    return (CHAR_FLAGS[c] & SPACE) != 0;
  }

  private enum State {
    // Request-Line   = Method SP Request-URI SP HTTP-Version CRLF
    REQUEST_METHOD,
    REQUEST_URI,
    REQUEST_VERSION_HTTP,
    REQUEST_VERSION_MAJOR,
    REQUEST_VERSION_MINOR,
    // message-header = field-name ":" [ field-value ]
    MESSAGE_HEADER_NAME,
    MESSAGE_HEADER_NAME_OR_CONTINUATION,
    MESSAGE_HEADER_VALUE,
  }

  private final SimpleHttpRequest.Builder builder = new SimpleHttpRequest.Builder();

  private State state;
  private StringBuilder elementBuffer;
  private int counter;
  private boolean expectLineFeed;
  private int headerFieldCount;

  private boolean done;

  private int majorVersion;
  private int minorVersion;
  private String unparsedUri;
  private String messageHeaderName;
  private String messageHeaderValue;

  IncrementalHttpRequestParser() {
    reset();
  }

  void reset() {
    state = State.REQUEST_METHOD;
    elementBuffer = new StringBuilder();
    counter = 0;
    expectLineFeed = false;
    headerFieldCount = 0;

    done = false;
    builder.reset();

    majorVersion = 0;
    minorVersion = 0;
    unparsedUri = null;
    messageHeaderName = null;
    messageHeaderValue = null;
  }

  private void trimAndAppendSpace() {
    if (elementBuffer.length() == 0) {
      // Trim all linear whitespace at the beginning.
    } else if (elementBuffer.charAt(elementBuffer.length() - 1) == ' ') {
      // Reduce all linear whitespace to a single space.
    } else {
      elementBuffer.append(' ');
    }
  }

  public int parse(byte[] input) {
    return parse(input, 0, input.length);
  }

  public int parse(byte[] input, int offset, int length) {
    if (done) {
      return 0;
    }

    for (int i = 0; i < length; i++) {
      final char c = (char) (input[offset + i] & 0xff);
      if (expectLineFeed) {
        expectLineFeed = false;
        if (c != '\n') {
          return setBadRequest("Expected <lf> following <cr>");
        }
      }
      switch (state) {
        case REQUEST_METHOD:
          if (c == ' ') {
            if (elementBuffer.length() == 0) {
              return setBadRequest("Expected request method, but <space> found");
            }
            builder.setMethod(elementBuffer.toString());
            counter = 0;
            elementBuffer.setLength(0);
            state = State.REQUEST_URI;
          } else if (elementBuffer.length() == 0 && (c == '\r' || c == '\n')) {
            // RFC 7230 §3.5: a server SHOULD ignore at least one empty line before the
            // request-line for robustness.
            if (c == '\r') {
              expectLineFeed = true;
            }
          } else if (isTokenCharacter(c)) {
            elementBuffer.append(c);
          } else {
            return setBadRequest("Illegal character in request method");
          }
          break;
        case REQUEST_URI: // "*" | absoluteURI | abs_path | authority
          if (c == ' ') {
            unparsedUri = elementBuffer.toString();
            builder.setUri(unparsedUri);
            counter = 0;
            elementBuffer.setLength(0);
            state = State.REQUEST_VERSION_HTTP;
          } else if (isControl(c)) {
            return setBadRequest("Illegal character in request URI");
          } else {
            if (elementBuffer.length() >= MAX_URI_LENGTH) {
              return setError(HttpStatusCode.URI_TOO_LONG);
            }
            // As of 2026-04, this encoding is probably unnecessary but predates the git history
            // (which goes back 9 years). Likely a workaround for a tool that sent these characters
            // literally in URIs.
            if (c == '|') {
              elementBuffer.append("%7C");
            } else if (c == '^') {
              elementBuffer.append("%5E");
            } else if (c == '`') {
              elementBuffer.append("%60");
            } else {
              elementBuffer.append(c);
            }
          }
          break;
        // HTTP-Version   = "HTTP" "/" 1*DIGIT "." 1*DIGIT
        case REQUEST_VERSION_HTTP:
          if ((counter == 0) && (c != 'H')) {
            return setBadRequest("Expected 'H' of request version string");
          } else if ((counter == 1) && (c != 'T')) {
            return setBadRequest("Expected 'T' of request version string");
          } else if ((counter == 2) && (c != 'T')) {
            return setBadRequest("Expected 'T' of request version string");
          } else if ((counter == 3) && (c != 'P')) {
            return setBadRequest("Expected 'P' of request version string");
          } else if ((counter == 4) && (c != '/')) {
            return setBadRequest("Expected '/' of request version string");
          }
          if (counter < 4) {
            counter++;
          } else {
            counter = 0;
            elementBuffer.setLength(0);
            state = State.REQUEST_VERSION_MAJOR;
          }
          break;
        case REQUEST_VERSION_MAJOR:
          if (isDigit(c)) {
            // Leading zeros MUST be ignored by recipients.
            if ((elementBuffer.length() == 1) && (elementBuffer.charAt(0) == '0')) {
              elementBuffer.setLength(0);
            }
            elementBuffer.append(c);
          } else if (c == '.') {
            if (elementBuffer.length() == 0) {
              return setBadRequest("Http major version number expected");
            }
            if (elementBuffer.length() > 7) {
              return setBadRequest("Http major version is too long");
            }
            String majorVersionString = elementBuffer.toString();
            if (!"1".equals(majorVersionString)) {
              return setError(HttpStatusCode.VERSION_NOT_SUPPORTED, "Http version not supported");
            }
            majorVersion = Integer.parseInt(majorVersionString);
            counter = 0;
            elementBuffer.setLength(0);
            state = State.REQUEST_VERSION_MINOR;
          } else {
            return setBadRequest("Expected '.' of request version string");
          }
          break;
        case REQUEST_VERSION_MINOR:
          if (isDigit(c)) {
            // Leading zeros MUST be ignored by recipients.
            if ((elementBuffer.length() == 1) && (elementBuffer.charAt(0) == '0')) {
              elementBuffer.setLength(0);
            }
            elementBuffer.append(c);
          } else if (c == '\r') {
            expectLineFeed = true;
          } else if (c == '\n') {
            if (elementBuffer.length() == 0) {
              return setBadRequest("Http minor version number expected");
            }
            if (elementBuffer.length() > 7) {
              return setBadRequest("Http minor version is too long");
            }
            minorVersion = Integer.parseInt(elementBuffer.toString());
            builder.setVersion(HttpVersion.of(majorVersion, minorVersion));
            if (minorVersion < 1) {
              return setError(HttpStatusCode.VERSION_NOT_SUPPORTED, "Http version not supported");
            }
            counter = 0;
            elementBuffer.setLength(0);
            state = State.MESSAGE_HEADER_NAME;
          } else {
            return setBadRequest("Expected end of request version string");
          }
          break;
        case MESSAGE_HEADER_NAME:
          if (c == ':') {
            if (elementBuffer.length() == 0) {
              return setBadRequest("Expected header field name, but ':' found");
            }
            messageHeaderName = elementBuffer.toString();
            counter = 0;
            elementBuffer.setLength(0);
            state = State.MESSAGE_HEADER_VALUE;
          } else if (c == '\r') {
            expectLineFeed = true;
          } else if (c == '\n') {
            if (elementBuffer.length() != 0) {
              return setBadRequest("Unexpected end of line in header field name");
            }
            return validateAndFinish(i);
          } else if (isTokenCharacter(c)) {
            if (elementBuffer.length() >= MAX_HEADER_NAME_LENGTH) {
              return setError(
                  HttpStatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE, "Header name is too long");
            }
            elementBuffer.append(c);
          } else {
            return setBadRequest("Illegal character in header field name");
          }
          break;
        case MESSAGE_HEADER_VALUE:
          if (c == '\r') {
            expectLineFeed = true;
          } else if (c == '\n') {
            int end = elementBuffer.length();
            // The trimAndAppendSpace ensures at most a single space here.
            if ((end > 0) && (elementBuffer.charAt(end - 1) == ' ')) {
              elementBuffer.setLength(end - 1);
            }
            messageHeaderValue = elementBuffer.toString();
            counter = 0;
            elementBuffer.setLength(0);
            state = State.MESSAGE_HEADER_NAME_OR_CONTINUATION;
          } else if (isSpace(c)) {
            trimAndAppendSpace();
          } else if (isControl(c)) {
            return setBadRequest("Illegal character in header field value");
          } else {
            if (elementBuffer.length() >= MAX_HEADER_VALUE_LENGTH) {
              return setError(
                  HttpStatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE, "Header value is too long");
            }
            elementBuffer.append(c);
          }
          break;
        case MESSAGE_HEADER_NAME_OR_CONTINUATION:
          if (isSpace(c)) {
            return setBadRequest("Line folding is obsolete and illegal (RFC 7230 s3.2.4)");
          }

          if (c == '\r') {
            expectLineFeed = true;
            break;
          }

          if (headerFieldCount >= MAX_HEADER_FIELD_COUNT) {
            return setError(
                HttpStatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE, "Too many header fields");
          }
          headerFieldCount++;
          builder.addHeader(messageHeaderName, messageHeaderValue);
          messageHeaderName = null;
          messageHeaderValue = null;

          if (c == '\n') {
            return validateAndFinish(i);
          } else if (isTokenCharacter(c)) {
            counter = 0;
            elementBuffer.setLength(0);
            state = State.MESSAGE_HEADER_NAME;
            elementBuffer.append(c);
          } else {
            return setBadRequest("Illegal character in header field name");
          }
          break;
        default:
          throw new RuntimeException("Not implemented!");
      }
    }
    return length;
  }

  /** Validates header-level constraints and marks parsing as done. Called at the blank line. */
  private int validateAndFinish(int i) {
    if (unparsedUri != null && !unparsedUri.equals("*") && !unparsedUri.startsWith("/")) {
      try {
        URI parsed = new URI(unparsedUri);
        if (!parsed.isAbsolute()) {
          return setBadRequest("Malformed URI");
        }
      } catch (URISyntaxException e) {
        return setBadRequest("Malformed URI");
      }
    }
    String contentLengthValue = builder.getHeader(HttpHeaderName.CONTENT_LENGTH);
    String transferEncodingValue = builder.getHeader(HttpHeaderName.TRANSFER_ENCODING);
    if (contentLengthValue != null && transferEncodingValue != null) {
      return setBadRequest("Must not set both Content-Length and Transfer-Encoding");
    }
    if (contentLengthValue != null) {
      try {
        long cl = Long.parseLong(contentLengthValue);
        if (cl < 0) {
          return setBadRequest("Illegal content length value");
        }
        if (cl > Integer.MAX_VALUE) {
          return setError(HttpStatusCode.PAYLOAD_TOO_LARGE);
        }
      } catch (NumberFormatException e) {
        return setBadRequest("Illegal content length value");
      }
    }
    if (transferEncodingValue != null && !"chunked".equalsIgnoreCase(transferEncodingValue)) {
      return setError(HttpStatusCode.NOT_IMPLEMENTED, "Unknown Transfer-Encoding");
    }
    if (majorVersion >= 1 && minorVersion >= 1 && builder.getHeader(HttpHeaderName.HOST) == null) {
      return setBadRequest("Missing 'Host' field");
    }
    done = true;
    return i + 1;
  }

  private int setBadRequest(String statusMessage) {
    return setError(HttpStatusCode.BAD_REQUEST, statusMessage);
  }

  private int setError(HttpStatusCode statusCode) {
    return setError(statusCode, statusCode.getStatusMessage());
  }

  private int setError(HttpStatusCode statusCode, String statusMessage) {
    builder.setError(statusCode, statusMessage);
    done = true;
    return 1;
  }

  public boolean isDone() {
    return done;
  }

  /**
   * Returns the parsed headers-only request. Must only be called after {@link #isDone()} returns
   * true. Throws {@link MalformedRequestException} if the request was malformed.
   */
  public HttpRequest getRequest() throws MalformedRequestException {
    if (!done) {
      throw new IllegalStateException("No parsed request available!");
    }
    if (builder.hasError()) {
      throw new MalformedRequestException(builder.getErrorResponse());
    }
    return builder.buildPartialRequest();
  }
}

package de.ofahrt.catfish.fastcgi;

final class IncrementalFcgiResponseParser {

  public interface Callback {
    void addHeader(String key, String value);
    void addData(byte[] data, int offset, int length);
  }

  public static class MalformedResponseException extends Exception {

    private static final long serialVersionUID = 1L;

    public MalformedResponseException(String msg) {
      super(msg);
    }
  }

  private static enum State {
    // message-header = field-name ":" [ field-value ]
    MESSAGE_HEADER_NAME, MESSAGE_HEADER_NAME_OR_CONTINUATION, MESSAGE_HEADER_VALUE,
    CONTENT;
  }

  private final Callback callback;
  private StringBuilder elementBuffer = new StringBuilder();
  private State state = State.MESSAGE_HEADER_NAME;
  private boolean expectLineFeed;

  private String messageHeaderName;
  private String messageHeaderValue;

  public IncrementalFcgiResponseParser(Callback callback) {
    this.callback = callback;
  }

  //       CTL            = <any US-ASCII control character
  //                        (octets 0 - 31) and DEL (127)>
  private static boolean isControl(char c) {
    return (c < 32) || (c == 127);
  }

  //       separators     = "(" | ")" | "<" | ">" | "@"
  //                      | "," | ";" | ":" | "\" | <">
  //                      | "/" | "[" | "]" | "?" | "="
  //                      | "{" | "}" | SP | HT
  private static boolean isSeparator(char c) {
    return (c == '(') || (c == ')') || (c == '<') || (c == '>') || (c == '@') ||
           (c == ',') || (c == ';') || (c == ':') || (c =='\\') || (c == '"') ||
           (c == '/') || (c == '[') || (c == ']') || (c == '?') || (c == '=') ||
           (c == '{') || (c == '}') || (c == ' ') || (c == '\t');
  }

  private static boolean isTokenCharacter(char c) {
    return !isControl(c) && !isSeparator(c);
  }

  private boolean isSpace(char c) {
    return (c == ' ') || (c == '\t');
  }

  private void trimAndAppendSpace() {
    if (elementBuffer.length() == 0) {
      // Trim all linear whitespace at the beginning.
    } else if (elementBuffer.charAt(elementBuffer.length()-1) == ' ') {
      // Reduce all linear whitespace to a single space.
    } else {
      elementBuffer.append(' ');
    }
  }

  public int parse(byte[] input) throws MalformedResponseException {
    return parse(input, 0, input.length);
  }

  public int parse(byte[] input, int offset, int length) throws MalformedResponseException {
    if (state == State.CONTENT) {
      callback.addData(input, offset, length);
      return length;
    }
    for (int i = 0; i < length; i++) {
      final char c = (char) (input[offset + i] & 0xff);
      if (expectLineFeed) {
        expectLineFeed = false;
        if (c != '\n') {
          throw new MalformedResponseException("Expected line feed character in state "+state);
        }
      }
      switch (state) {
        case MESSAGE_HEADER_NAME :
          if (c == ':') {
            if (elementBuffer.length() == 0) {
              throw new MalformedResponseException("Expected message header field name, but ':' found");
            }
            messageHeaderName = elementBuffer.toString();
            elementBuffer.setLength(0);
            state = State.MESSAGE_HEADER_VALUE;
          } else if (c == '\r') {
            expectLineFeed = true;
          } else if (c == '\n') {
            if (elementBuffer.length() != 0) {
              throw new MalformedResponseException("Unexpected end of line in message header field name");
            }
            state = State.CONTENT;
          } else if (isTokenCharacter(c)) {
            elementBuffer.append(c);
          } else {
            throw new MalformedResponseException("Illegal character in request method");
          }
          break;
        case MESSAGE_HEADER_VALUE :
          if (c == '\r') {
            expectLineFeed = true;
          } else if (c == '\n') {
            int end = elementBuffer.length();
            while ((end > 0) && (elementBuffer.charAt(end-1) == ' ')) {
              end--;
            }
            elementBuffer.setLength(end);
            messageHeaderValue = elementBuffer.toString();
            elementBuffer.setLength(0);
            state = State.MESSAGE_HEADER_NAME_OR_CONTINUATION;
          } else if (isSpace(c)) {
            trimAndAppendSpace();
          } else {
            elementBuffer.append(c);
          }
          break;
        case MESSAGE_HEADER_NAME_OR_CONTINUATION :
          if (isSpace(c)) {
            state = State.MESSAGE_HEADER_VALUE;
            elementBuffer.append(messageHeaderValue);
            trimAndAppendSpace();
            break;
          }

          if (c == '\r') {
            expectLineFeed = true;
            break;
          }

          callback.addHeader(messageHeaderName, messageHeaderValue);
          messageHeaderName = null;
          messageHeaderValue = null;

          if (c == '\n') {
            state = State.CONTENT;
          } else if (isTokenCharacter(c)) {
            elementBuffer.setLength(0);
            state = State.MESSAGE_HEADER_NAME;
            elementBuffer.append(c);
          } else {
            throw new MalformedResponseException("Illegal character in request header name");
          }
          break;
        case CONTENT :
          callback.addData(input, offset + i, length - i);
          return length;
        default :
          throw new RuntimeException("Not implemented!");
      }
    }
    return length;
  }
}

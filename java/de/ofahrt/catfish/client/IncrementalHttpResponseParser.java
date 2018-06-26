package de.ofahrt.catfish.client;

import java.util.Arrays;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.SimpleHttpResponse;

final class IncrementalHttpResponseParser {

  private static enum State {
    // Status-Line = HTTP-Version SP Status-Code SP Reason-Phrase CRLF
    RESPONSE_VERSION_HTTP, RESPONSE_VERSION_MAJOR, RESPONSE_VERSION_MINOR, RESPONSE_CODE, RESPONSE_REASON_PHRASE,
    // message-header = field-name ":" [ field-value ]
    MESSAGE_HEADER_NAME, MESSAGE_HEADER_NAME_OR_CONTINUATION, MESSAGE_HEADER_VALUE,
    CONTENT,
    CHUNKED_CONTENT_LENGTH,
    CHUNKED_CONTENT_DATA,
    CHUNKED_CONTENT_NEXT;
  }

  private final int maxContentLength = 1000000;

  private SimpleHttpResponse.Builder response = new SimpleHttpResponse.Builder();
  private StringBuilder elementBuffer = new StringBuilder();
  private State state = State.RESPONSE_VERSION_HTTP;
  private int counter = 0;
  private boolean expectLineFeed;

  private String messageHeaderName;
  private String messageHeaderValue;

  private byte[] content;
  private int contentIndex;

  private boolean done;

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

  private boolean isDigit(char c) {
    return (c >= '0') && (c <= '9');
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
    for (int i = 0; i < length; i++) {
      final char c = (char) (input[offset + i] & 0xff);
      if (expectLineFeed) {
        expectLineFeed = false;
        if (c != '\n') {
          throw new MalformedResponseException("Expected line feed character in state "+state);
        }
      }
      switch (state) {
        case RESPONSE_VERSION_HTTP :
          if ((counter == 0) && (c != 'H')) {
            throw new MalformedResponseException("Expected 'H' of response version string");
          } else if ((counter == 1) && (c != 'T')) {
            throw new MalformedResponseException("Expected 'T' of response version string");
          } else if ((counter == 2) && (c != 'T')) {
            throw new MalformedResponseException("Expected 'T' of response version string");
          } else if ((counter == 3) && (c != 'P')) {
            throw new MalformedResponseException("Expected 'P' of response version string");
          } else if ((counter == 4) && (c != '/')) {
            throw new MalformedResponseException("Expected '/' of response version string");
          }
          if (counter < 4) {
            counter++;
          } else {
            counter = 0;
            elementBuffer.setLength(0);
            state = State.RESPONSE_VERSION_MAJOR;
          }
          break;
        case RESPONSE_VERSION_MAJOR :
          if (isDigit(c)) {
            // Leading zeros MUST be ignored by recipients.
            if ((elementBuffer.length() == 1) && (elementBuffer.charAt(0) == '0')) {
              elementBuffer.setLength(0);
            }
            elementBuffer.append(c);
            if (elementBuffer.length() > 9) {
              throw new MalformedResponseException("Http major version number is too long");
            }
          }
          else if (c == '.') {
            if (elementBuffer.length() == 0) {
              throw new MalformedResponseException("Http major version number expected");
            }
            String majorVersionString = elementBuffer.toString();
            if (!"0".equals(majorVersionString) && !"1".equals(majorVersionString)) {
              throw new UnsupportedOperationException("Http version not supported");
            }
            response.setMajorVersion(Integer.parseInt(majorVersionString));
            counter = 0;
            elementBuffer.setLength(0);
            state = State.RESPONSE_VERSION_MINOR;
          } else {
            throw new MalformedResponseException("Expected '.' of response version string");
          }
          break;
        case RESPONSE_VERSION_MINOR :
          if (isDigit(c)) {
            // Leading zeros MUST be ignored by recipients.
            if ((elementBuffer.length() == 1) && (elementBuffer.charAt(0) == '0')) {
              elementBuffer.setLength(0);
            }
            elementBuffer.append(c);
            if (elementBuffer.length() > 9) {
              throw new MalformedResponseException("Http minor version number is too long");
            }
          } else if (c == ' ') {
            if (elementBuffer.length() == 0) {
              throw new MalformedResponseException("Http minor version number expected");
            }
            if (elementBuffer.length() > 7) {
              throw new MalformedResponseException("Http minor version too long");
            }
            response.setMinorVersion(Integer.parseInt(elementBuffer.toString()));
            counter = 0;
            elementBuffer.setLength(0);
            state = State.RESPONSE_CODE;
          } else {
            throw new MalformedResponseException("Expected end of response version string");
          }
          break;
        case RESPONSE_CODE :
          if (isDigit(c)) {
            elementBuffer.append(c);
            if (elementBuffer.length() > 3) {
              throw new MalformedResponseException("Status code is too long");
            }
          } else if (c == ' ') {
            if (elementBuffer.length() != 3) {
              throw new MalformedResponseException("Status code is too short; 3 digits expected");
            }
            if (elementBuffer.charAt(0) == '0') {
              throw new MalformedResponseException("Leading zero in status code, what now?");
            }
            response.setStatusCode(Integer.parseInt(elementBuffer.toString()));
            counter = 0;
            elementBuffer.setLength(0);
            state = State.RESPONSE_REASON_PHRASE;
          } else {
            throw new MalformedResponseException("Expected status code digit");
          }
          break;
        case RESPONSE_REASON_PHRASE :
          if (c == '\r') {
            expectLineFeed = true;
          } else if (c == '\n') {
            response.setReasonPhrase(elementBuffer.toString());
            elementBuffer.setLength(0);
            state = State.MESSAGE_HEADER_NAME;
          } else {
            elementBuffer.append(c);
          }
          break;
        case MESSAGE_HEADER_NAME :
          if (c == ':') {
            if (elementBuffer.length() == 0) {
              throw new MalformedResponseException("Expected message header field name, but ':' found");
            }
            messageHeaderName = elementBuffer.toString();
            counter = 0;
            elementBuffer.setLength(0);
            state = State.MESSAGE_HEADER_VALUE;
          } else if (c == '\r') {
            expectLineFeed = true;
          } else if (c == '\n') {
            if (elementBuffer.length() != 0) {
              throw new MalformedResponseException("Unexpected end of line in message header field name");
            }
            done = true;
            return i + 1;
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
            counter = 0;
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

          response.addHeader(messageHeaderName, messageHeaderValue);
          messageHeaderName = null;
          messageHeaderValue = null;

          if (c == '\n') {
            String contentLengthValue = response.getHeader(HttpHeaderName.CONTENT_LENGTH);
            if (contentLengthValue != null) {
              long contentLength;
              try {
                contentLength = Long.parseLong(contentLengthValue);
              } catch (NumberFormatException e) {
                throw new MalformedResponseException("Illegal content length value");
              }
              if (contentLength > maxContentLength) {
                throw new MalformedResponseException("Too large content length");
              }
              if (contentLength == 0) {
                done = true;
                return i + 1;
              }
              content = new byte[(int) contentLength];
              contentIndex = 0;
              state = State.CONTENT;
            } else if (response.getHeader(HttpHeaderName.TRANSFER_ENCODING) != null) {
              String transferEncoding = response.getHeader(HttpHeaderName.TRANSFER_ENCODING);
              if ("chunked".equals(transferEncoding)) {
                state = State.CHUNKED_CONTENT_LENGTH;
              }
            } else {
              done = true;
              return i + 1;
            }
          } else if (isTokenCharacter(c)) {
            counter = 0;
            elementBuffer.setLength(0);
            state = State.MESSAGE_HEADER_NAME;
            elementBuffer.append(c);
          } else {
            throw new MalformedResponseException("Illegal character in request header name");
          }
          break;
        case CONTENT : {
            int maxCopy = Math.min(length - i, content.length - contentIndex);
            System.arraycopy(input, offset + i, content, contentIndex, maxCopy);
            i += maxCopy;
            contentIndex += maxCopy;
            if (contentIndex == content.length) {
              response.setBody(content);
              done = true;
              return i;
            }
          }
          break;
        case CHUNKED_CONTENT_LENGTH :
          if (c == '\r') {
            expectLineFeed = true;
          } else if (c == '\n') {
            String chunkLength = elementBuffer.toString();
            elementBuffer.setLength(0);
            int parsedChunkLength = Integer.parseInt(chunkLength, 16);
            if (parsedChunkLength == 0) {
              response.setBody(content);
              done = true;
              return i + 1;
            }
            if (content == null) {
              content = new byte[parsedChunkLength];
            } else {
              if (parsedChunkLength > maxContentLength) {
                throw new MalformedResponseException("Too large chunk");
              }
              if (content.length > maxContentLength - parsedChunkLength) {
                throw new MalformedResponseException("Too large content length");
              }
              content = Arrays.copyOf(content, content.length + parsedChunkLength);
            }
            state = State.CHUNKED_CONTENT_DATA;
          } else if (((c >= '0') && (c <= '9')) || ((c >= 'A') && (c <= 'F')) || ((c >= 'a') && (c <= 'f'))) {
            elementBuffer.append(c);
          } else {
            throw new MalformedResponseException("Illegal character in chunked content length");
          }
          break;
        case CHUNKED_CONTENT_DATA : {
            int maxCopy = Math.min(length - i, content.length - contentIndex);
            System.arraycopy(input, offset + i, content, contentIndex, maxCopy);
            i += maxCopy - 1;
            contentIndex += maxCopy;
            if (contentIndex == content.length) {
              state = State.CHUNKED_CONTENT_NEXT;
            }
          }
          break;
        case CHUNKED_CONTENT_NEXT :
          if (c == '\r') {
            expectLineFeed = true;
          } else if (c == '\n') {
            state = State.CHUNKED_CONTENT_LENGTH;
          } else {
            throw new MalformedResponseException("Expected line break");
          }
          break;
        default :
          throw new RuntimeException("Not implemented!");
      }
    }
    return length;
  }

  public boolean isDone() {
    return done;
  }

  public HttpResponse getResponse() {
    if (!done) {
      throw new IllegalStateException("No parsed response available!");
    }
    return response.build();
  }
}

package de.ofahrt.catfish;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

import javax.servlet.http.HttpServletResponse;

import de.ofahrt.catfish.utils.HttpFieldName;

final class IncrementalHttpRequestParser {

	private static enum State {
		// Request-Line   = Method SP Request-URI SP HTTP-Version CRLF
		REQUEST_METHOD, REQUEST_URI, REQUEST_VERSION_HTTP, REQUEST_VERSION_MAJOR, REQUEST_VERSION_MINOR,
		// message-header = field-name ":" [ field-value ]
		MESSAGE_HEADER_NAME, MESSAGE_HEADER_NAME_OR_CONTINUATION, MESSAGE_HEADER_VALUE,
		CONTENT;
	}

  private final RequestImpl.Builder request;
  private final int maxContentLength;

  private State state = State.REQUEST_METHOD;
  private StringBuilder elementBuffer = new StringBuilder();
  private int counter = 0;
  private boolean expectLineFeed = false;

  private boolean done = false;

  private String messageHeaderName;
  private String messageHeaderValue;

  private byte[] content;
  private int contentIndex;

  public IncrementalHttpRequestParser(
      InetSocketAddress localAddress, InetSocketAddress remoteAddress, boolean secure) {
    request = new RequestImpl.Builder();
    request.setLocalAddress(localAddress);
    request.setClientAddress(remoteAddress);
    request.setSecure(secure);
  	this.maxContentLength = 1000000;
  }

  void reset() {
  	state = State.REQUEST_METHOD;
  	elementBuffer = new StringBuilder();
  	counter = 0;
  	expectLineFeed = false;

  	done = false;
  	request.reset();

  	messageHeaderName = null;
  	messageHeaderValue = null;

  	content = null;
  	contentIndex = 0;
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

  static boolean isTokenCharacter(char c) {
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
  				return setError("Expected <lf> following <cr>");
  			}
  		}
  		switch (state) {
  			case REQUEST_METHOD :
  				if (c == ' ') {
  					if (elementBuffer.length() == 0) {
  						return setError("Expected request method, but <space> found");
  					}
  					request.setMethod(elementBuffer.toString());
  					counter = 0;
  					elementBuffer.setLength(0);
  					state = State.REQUEST_URI;
  				} else if (isTokenCharacter(c)) {
            elementBuffer.append(c);
  				} else {
            return setError("Illegal character in request method");
  				}
  				break;
  			case REQUEST_URI : // "*" | absoluteURI | abs_path | authority
  				if (c == ' ') {
  					String unparsedUri = elementBuffer.toString();
  					request.setUnparsedUri(unparsedUri);
  					if (!"*".equals(unparsedUri)) {
  						try {
  						  request.setUri(new URI(unparsedUri));
  						} catch (URISyntaxException e) {
  						  // TODO: Maybe return the exception as well?
  						  return setError("Bad URI syntax");
  						}
  					}
  					counter = 0;
  					elementBuffer.setLength(0);
  					state = State.REQUEST_VERSION_HTTP;
  				} else if ((c == '\r') || (c == '\n')) {
  				  // TODO: This probably shouldn't allow any control characters.
  					return setError("Unexpected end of line in request uri");
  				} else if (c == '|') {
  					elementBuffer.append(CoreHelper.encode('|'));
  				} else if (c == '^') {
  					elementBuffer.append(CoreHelper.encode('^'));
  				} else if (c == '`') {
  					elementBuffer.append(CoreHelper.encode('`'));
  				} else {
  					elementBuffer.append(c);
  				}
  				break;
  			// HTTP-Version   = "HTTP" "/" 1*DIGIT "." 1*DIGIT
  			case REQUEST_VERSION_HTTP :
  				if ((counter == 0) && (c != 'H')) {
            return setError("Expected 'H' of request version string");
  				} else if ((counter == 1) && (c != 'T')) {
  					return setError("Expected 'T' of request version string");
  				} else if ((counter == 2) && (c != 'T')) {
  					return setError("Expected 'T' of request version string");
  				} else if ((counter == 3) && (c != 'P')) {
  					return setError("Expected 'P' of request version string");
  				} else if ((counter == 4) && (c != '/')) {
  					return setError("Expected '/' of request version string");
  				}
  				if (counter < 4) {
  					counter++;
  				} else {
  					counter = 0;
  					elementBuffer.setLength(0);
  					state = State.REQUEST_VERSION_MAJOR;
  				}
  				break;
  			case REQUEST_VERSION_MAJOR :
  				if (isDigit(c)) {
  					// Leading zeros MUST be ignored by recipients.
  					if ((elementBuffer.length() == 1) && (elementBuffer.charAt(0) == '0')) {
  						elementBuffer.setLength(0);
  					}
  					elementBuffer.append(c);
  				}
  				else if (c == '.') {
  					if (elementBuffer.length() == 0) {
              return setError("Http major version number expected");
  					}
            if (elementBuffer.length() > 7) {
              return setError("Http major version is too long");
            }
  					String majorVersionString = elementBuffer.toString();
  					if (!"0".equals(majorVersionString) && !"1".equals(majorVersionString)) {
  						return setError(HttpServletResponse.SC_HTTP_VERSION_NOT_SUPPORTED, "Http version not supported");
  					}
  					request.setMajorVersion(Integer.parseInt(majorVersionString));
  					counter = 0;
  					elementBuffer.setLength(0);
  					state = State.REQUEST_VERSION_MINOR;
  				} else {
            return setError("Expected '.' of request version string");
  				}
  				break;
  			case REQUEST_VERSION_MINOR :
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
  						return setError("Http minor version number expected");
  					}
  					if (elementBuffer.length() > 7) {
  						return setError("Http minor version is too long");
  					}
  					request.setMinorVersion(Integer.parseInt(elementBuffer.toString()));
  					counter = 0;
  					elementBuffer.setLength(0);
  					state = State.MESSAGE_HEADER_NAME;
  				} else {
  				  return setError("Expected end of request version string");
  				}
  				break;
  			case MESSAGE_HEADER_NAME :
  				if (c == ':') {
  					if (elementBuffer.length() == 0) {
              return setError("Expected header field name, but ':' found");
  					}
  					messageHeaderName = elementBuffer.toString();
  					counter = 0;
  					elementBuffer.setLength(0);
  					state = State.MESSAGE_HEADER_VALUE;
  				} else if (c == '\r') {
  					expectLineFeed = true;
  		    } else if (c == '\n') {
  					if (elementBuffer.length() != 0) {
              return setError("Unexpected end of line in header field name");
  					}
  					done = true;
  					return i + 1;
  				} else if (isTokenCharacter(c)) {
  					elementBuffer.append(c);
  				} else {
            return setError("Illegal character in header field name");
  				}
  				break;
  			case MESSAGE_HEADER_VALUE :
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

  				request.addHeader(messageHeaderName, messageHeaderValue);
  				messageHeaderName = null;
  				messageHeaderValue = null;

  				if (c == '\n') {
  					String contentLengthValue = request.getHeader(HttpFieldName.CONTENT_LENGTH);
  					if (contentLengthValue != null) {
  						long contentLength;
  						try {
  							contentLength = Long.parseLong(contentLengthValue);
  						} catch (NumberFormatException e) {
  						  return setError("Illegal content length value");
  						}
  						if (contentLength > maxContentLength) {
  							return setError("Content length larger than allowed");
  						}
  						if (contentLength == 0) {
  						  done = true;
                return i + 1;
  						}
  						content = new byte[(int) contentLength];
  						contentIndex = 0;
  						state = State.CONTENT;
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
            return setError("Illegal character in header field name");
          }
  				break;
  			case CONTENT :
  				int maxCopy = Math.min(length - i, content.length-contentIndex);
  				System.arraycopy(input, offset + i, content, contentIndex, maxCopy);
  				i += maxCopy;
  				contentIndex += maxCopy;
  				if (contentIndex == content.length) {
  				  request.setBody(content);
  					done = true;
  					return i;
  				}
  				break;
  			default :
  				throw new RuntimeException("Not implemented!");
  		}
  	}
  	return length;
  }

  private int setError(String error) {
    return setError(HttpServletResponse.SC_BAD_REQUEST, error);
  }

  private int setError(int errorCode, String error) {
    request.setError(errorCode, error);
    done = true;
    return 1;
  }

  public boolean isDone() {
    return done;
  }

  public RequestImpl getRequest() {
  	if (!done) {
  	  throw new IllegalStateException("No parsed request available!");
  	}
  	return request.build();
  }
}

package de.ofahrt.catfish.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.utils.HttpContentType;
import de.ofahrt.catfish.utils.HttpDate;
import de.ofahrt.catfish.utils.MimeType;

public final class ResponseImpl implements HttpServletResponse {
  private final HttpRequest request;
  private final HttpResponseWriter responseWriter;
  private final ResponsePolicy policy;

  private boolean isCommitted;
  private boolean isCompleted;

  private HttpVersion version = HttpVersion.HTTP_1_1;
  private String charset = "UTF-8";
  private String contentType;
  private int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
  private Locale defaultLocale = Locale.US;
  private Locale locale;

  private OutputStream keepStream;

  private HashMap<String, String> header = new HashMap<>();

  ResponseImpl(HttpRequest request, HttpResponseWriter responseWriter) {
    this.request = request;
    this.responseWriter = responseWriter;
    this.policy = responseWriter == null ? null : responseWriter.getResponsePolicy();
  }

  void setVersion(HttpVersion version) {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    this.version = version;
  }

  private String canonicalize(String key) {
    return HttpHeaderName.canonicalize(key);
  }

  private void setHeaderInternal(String key, String value) {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    header.put(canonicalize(key), value);
  }

  private String getHeader(String key) {
    return header.get(canonicalize(key));
  }

  void setCookie(String cookie) {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    setHeader(HttpHeaderName.SET_COOKIE, cookie);
  }

  private OutputStream internalOutputStream() {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    if (keepStream != null) {
      throw new IllegalStateException();
    }

    // TODO: The charset may not be set yet. Store the content type as a field and only set the
    // header when finalizing the response.
    String mimeType = MimeType.APPLICATION_OCTET_STREAM.toString();
    if (contentType != null) {
      mimeType = HttpContentType.getMimeTypeFromContentType(contentType);
      String type = contentType;
      if (isTextMimeType(type) && (charset != null)) {
        type += "; charset=" + charset;
      }
      setHeaderInternal(HttpHeaderName.CONTENT_TYPE, type);
    }

    final int bufferSize = 512;
    OutputStream internalStream = new OutputStream() {
      private OutputStream bufferOrForward = new ByteArrayOutputStream(bufferSize);

      @Override
      public void close() throws IOException {
        if (bufferOrForward == null) {
          return;
        }
        flush();
        bufferOrForward.close();
        bufferOrForward = null;
      }

      @Override
      public void flush() throws IOException {
        if (bufferOrForward == null) {
          throw new IOException("Output stream already closed");
        }
        if (isCommitted) {
          bufferOrForward.flush();
          return;
        }
        OutputStream forward = responseWriter.commitStreamed(commitResponse());
        forward.write(((ByteArrayOutputStream) bufferOrForward).toByteArray());
        bufferOrForward = forward;
      }

      @Override
      public void write(int b) throws IOException {
        if (bufferOrForward == null) {
          throw new IOException("Output stream already closed");
        }
        bufferOrForward.write(b);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        if (bufferOrForward == null) {
          throw new IOException("Output stream already closed");
        }
        if (bufferOrForward instanceof ByteArrayOutputStream) {
          ByteArrayOutputStream buffer = (ByteArrayOutputStream) bufferOrForward;
          if (buffer.size() + len > bufferSize) {
            flush();
          }
        }
        bufferOrForward.write(b, off, len);
      }
    };

    if (policy.shouldCompress(request, mimeType)) {
      setHeaderInternal(HttpHeaderName.CONTENT_ENCODING, "gzip");
      try {
        keepStream = new GZIPOutputStream(internalStream);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    } else {
      keepStream = internalStream;
    }
    return keepStream;
  }

  private static boolean isTextMimeType(String name) {
    return name.startsWith("text/");
  }

  private HttpResponse commitResponse() {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    isCommitted = true;
    // The servlet specification requires writing out the content-language.
    // But locale.toString is clearly not correct.
    // if ((locale != null) && containsHeader(HttpFieldName.CONTENT_TYPE))
    // setHeaderInternal(HttpFieldName.CONTENT_LANGUAGE, locale.toString());
    final HttpVersion committedVersion = version;
    final int committedStatus = status;
    final HttpHeaders committedHeaders = HttpHeaders.of(header);
    return new HttpResponse() {
      @Override
      public HttpVersion getProtocolVersion() {
        return committedVersion;
      }

      @Override
      public int getStatusCode() {
        return committedStatus;
      }

      @Override
      public HttpHeaders getHeaders() {
        return committedHeaders;
      }
    };
  }

  public void close() throws IOException {
    if (isCompleted) {
      return;
    }
    if (keepStream != null) {
      keepStream.close();
    }
    if (!isCommitted) {
      getOutputStream().close();
    }
    isCompleted = true;
  }

  // ServletResponse API Implementation
  @Override
  public void flushBuffer() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getBufferSize() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getCharacterEncoding() {
    return charset;
  }

  @Override
  public String getContentType() {
    return getHeader(HttpHeaderName.CONTENT_TYPE);
  }

  @Override
  public Locale getLocale() {
    return locale != null ? locale : defaultLocale;
  }

  @SuppressWarnings("resource")
  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    final OutputStream forwardOut = internalOutputStream();
    return new ServletOutputStream() {
      @Override
      public void write(int b) throws IOException {
        forwardOut.write(b);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        forwardOut.write(b, off, len);
      }

      @Override
      public void flush() throws IOException {
        forwardOut.flush();
      }

      @Override
      public void close() throws IOException {
        forwardOut.close();
      }
    };
  }

  @Override
  public PrintWriter getWriter() {
    Charset javaCharset = StandardCharsets.UTF_8;
    return new PrintWriter(new OutputStreamWriter(internalOutputStream(), javaCharset));
  }

  @Override
  public boolean isCommitted() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void reset() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void resetBuffer() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setBufferSize(int size) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setCharacterEncoding(String charset) {
    if (isCommitted) {
      return;
    }
    this.charset = charset;
  }

  @Override
  public void setContentLength(int len) {
    // Ignore servlet-provided setting. It might be wrong, and we're going to override it anyway
    // (or use chunked encoding). We allow this to go through even when the response is already
    // committed.
  }

  @Override
  public void setContentType(String type) {
    if (isCommitted) {
      return;
    }
    if (type == null) {
      throw new NullPointerException();
    }
    this.contentType = type;
  }

  @Override
  public void setLocale(Locale locale) {
    if (isCommitted) {
      return;
    }
    this.locale = locale;
  }

  // HttpServletResponse API Implementation
  @Override
  public void addCookie(Cookie cookie) {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    throw new UnsupportedOperationException();
  }

  @Override
  public void addDateHeader(String name, long date) {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    addHeader(canonicalize(name), HttpDate.formatDate(date));
  }

  @Override
  public void addHeader(String name, String value) {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    throw new UnsupportedOperationException();
  }

  @Override
  public void addIntHeader(String name, int value) {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    addHeader(name, Integer.toString(value));
  }

  @Override
  public boolean containsHeader(String name) {
    return header.containsKey(canonicalize(name));
  }

  @Override
  @Deprecated
  public String encodeRedirectUrl(String url) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String encodeRedirectURL(String url) {
    throw new UnsupportedOperationException();
  }

  @Override
  @Deprecated
  public String encodeUrl(String url) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String encodeURL(String url) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void sendError(int statusCode) {
    sendError(statusCode, null);
  }

  @Override
  public void sendError(int statusCode, String msg) {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    setStatus(statusCode);
    if (msg == null) {
      msg = HttpStatusCode.getStatusMessage(statusCode);
    }
    // TODO: This should be text/html according to the spec.
    setContentType(MimeType.TEXT_PLAIN.toString());
    HttpResponse response = commitResponse().withBody(msg.getBytes(StandardCharsets.UTF_8));
    responseWriter.commitBuffered(response);
  }

  @Override
  public void sendRedirect(String location) throws IOException {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    // TODO: Location needs to be resolved relative to the request URI.
    setStatus(HttpServletResponse.SC_FOUND);
    setHeader(HttpHeaderName.LOCATION, location);
    setContentType(MimeType.TEXT_HTML.toString());
    String msg =
        "<html><head><meta http-equiv=\"refresh\" content=\"1; URL="
        + location
        + "\"></head><body>REDIRECT</body></html>";
    HttpResponse response = commitResponse().withBody(msg.getBytes(StandardCharsets.UTF_8));
    responseWriter.commitBuffered(response);
  }

  @Override
  public void setDateHeader(String name, long date) {
    setHeaderInternal(name, HttpDate.formatDate(date));
  }

  @Override
  public void setHeader(String name, String value) {
    setHeaderInternal(name, value);
  }

  @Override
  public void setIntHeader(String name, int value) {
    setHeaderInternal(name, Integer.toString(value));
  }

  @Override
  public void setStatus(int status) {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    if ((status < 100) || (status > 999)) {
      throw new IllegalArgumentException("The status must be a positive three-digit number");
    }
    this.status = status;
  }

  @Override
  @Deprecated
  public void setStatus(int status, String sm) {
    if (isCommitted) {
      throw new IllegalStateException();
    }
    throw new UnsupportedOperationException();
  }
}

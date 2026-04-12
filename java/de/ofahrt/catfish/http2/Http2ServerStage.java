package de.ofahrt.catfish.http2;

import de.ofahrt.catfish.http2.Hpack.Header;
import de.ofahrt.catfish.http2.HpackDecoder.HpackDecodingException;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.RequestAction;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * HTTP/2 server stage (RFC 9113). Handles the connection preface, SETTINGS exchange, HPACK-decoded
 * HEADERS dispatch to an {@link HttpHandler}, DATA frame body accumulation, and multiplexed
 * response writing.
 *
 * <p>This stage is created by the TLS layer after ALPN negotiates "h2". It reuses the existing
 * {@link HttpHandler} interface — pseudo-headers are translated transparently into method, URI, and
 * Host header.
 */
public final class Http2ServerStage implements Stage {

  /** Callback to dispatch a parsed request to the handler on an executor thread. */
  public interface RequestQueue {
    void queueRequest(
        HttpHandler httpHandler,
        Connection connection,
        HttpRequest request,
        HttpResponseWriter responseWriter);
  }

  private static final byte[] CLIENT_PREFACE =
      "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

  private static final int DEFAULT_MAX_FRAME_SIZE = 16384;
  private static final int DEFAULT_MAX_CONCURRENT_STREAMS = 100;

  private final Pipeline parent;
  private final RequestQueue requestHandler;
  private final ConnectHandler connectHandler;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;

  private @Nullable Connection connection;
  private final HpackDecoder hpackDecoder = new HpackDecoder();
  private final HpackEncoder hpackEncoder = new HpackEncoder();
  private final Http2FrameReader frameReader = new Http2FrameReader();

  private final Map<Integer, Http2Stream> streams = new LinkedHashMap<>();

  // Connection preface state.
  private int prefaceOffset;
  private boolean prefaceReceived;
  private boolean serverPrefaceSent;

  // Peer settings.
  private int peerMaxFrameSize = DEFAULT_MAX_FRAME_SIZE;

  // Control frame queue (accumulated during read(), drained during write()).
  // We use a simple byte buffer for queued control frames.
  private final ByteBuffer controlFrameBuffer = ByteBuffer.allocate(4096);

  // Connection-level receive window: auto-ack DATA.
  private int connectionRecvWindow = 65535; // initial value per spec

  // Last stream ID we processed (for GOAWAY).
  private int lastStreamId;
  private boolean goawaySent;

  public Http2ServerStage(
      Pipeline parent,
      RequestQueue requestHandler,
      ConnectHandler connectHandler,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this.parent = parent;
    this.requestHandler = requestHandler;
    this.connectHandler = connectHandler;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    controlFrameBuffer.flip(); // start in read mode (empty)
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    this.connection = connection;
    return InitialConnectionState.READ_ONLY;
  }

  @Override
  public ConnectionControl read() throws IOException {
    if (!prefaceReceived) {
      if (!consumePreface()) {
        return ConnectionControl.NEED_MORE_DATA;
      }
      prefaceReceived = true;
      // Queue server SETTINGS.
      queueServerSettings();
      parent.encourageWrites();
    }

    // Parse frames from input buffer.
    while (inputBuffer.hasRemaining()) {
      int consumed =
          frameReader.parse(
              inputBuffer.array(),
              inputBuffer.arrayOffset() + inputBuffer.position(),
              inputBuffer.remaining());
      inputBuffer.position(inputBuffer.position() + consumed);

      if (!frameReader.isComplete()) {
        return ConnectionControl.NEED_MORE_DATA;
      }

      processFrame();
      frameReader.reset();
    }

    return ConnectionControl.NEED_MORE_DATA;
  }

  @Override
  public void inputClosed() throws IOException {
    // Client closed the connection.
  }

  @Override
  public ConnectionControl write() throws IOException {
    outputBuffer.compact();

    if (!serverPrefaceSent) {
      // Drain queued server SETTINGS into output.
      drainControlFrames(outputBuffer);
      serverPrefaceSent = true;
    } else {
      drainControlFrames(outputBuffer);
    }

    // Write response frames for streams that have pending data.
    for (var it = streams.entrySet().iterator(); it.hasNext(); ) {
      var entry = it.next();
      Http2Stream stream = entry.getValue();
      if (stream.isResponseReady()) {
        boolean done = stream.writeResponseFrames(outputBuffer, peerMaxFrameSize);
        if (done) {
          it.remove();
        } else {
          break; // output buffer full, try again later
        }
      }
    }

    outputBuffer.flip();

    if (outputBuffer.hasRemaining()) {
      return ConnectionControl.CONTINUE;
    }
    if (goawaySent && streams.isEmpty()) {
      return ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
    }
    return ConnectionControl.PAUSE;
  }

  @Override
  public void close() {
    streams.clear();
  }

  // ---- Connection preface ----

  private boolean consumePreface() {
    while (inputBuffer.hasRemaining() && prefaceOffset < CLIENT_PREFACE.length) {
      byte b = inputBuffer.get();
      if (b != CLIENT_PREFACE[prefaceOffset]) {
        throw new IllegalStateException("Invalid HTTP/2 client connection preface");
      }
      prefaceOffset++;
    }
    return prefaceOffset >= CLIENT_PREFACE.length;
  }

  private void queueServerSettings() {
    controlFrameBuffer.compact();
    Http2FrameWriter.writeSettings(
        controlFrameBuffer,
        0x3,
        DEFAULT_MAX_CONCURRENT_STREAMS, // SETTINGS_MAX_CONCURRENT_STREAMS
        0x2,
        0); // SETTINGS_ENABLE_PUSH = 0
    controlFrameBuffer.flip();
  }

  // ---- Frame dispatch ----

  private void processFrame() throws IOException {
    int type = frameReader.getType();
    switch (type) {
      case FrameType.SETTINGS -> handleSettings();
      case FrameType.HEADERS -> handleHeaders();
      case FrameType.DATA -> handleData();
      case FrameType.PING -> handlePing();
      case FrameType.WINDOW_UPDATE -> handleWindowUpdate();
      case FrameType.RST_STREAM -> handleRstStream();
      case FrameType.GOAWAY -> handleGoaway();
      case FrameType.PRIORITY -> {} // ignore
      default -> {} // unknown frame types are ignored per spec
    }
  }

  // ---- SETTINGS ----

  private void handleSettings() {
    if (frameReader.hasFlag(Http2FrameReader.FLAG_ACK)) {
      // Acknowledgement of our settings — nothing to do.
      return;
    }
    byte[] payload = frameReader.getPayload();
    if (payload != null) {
      for (int i = 0; i + 5 < payload.length; i += 6) {
        int id = ((payload[i] & 0xff) << 8) | (payload[i + 1] & 0xff);
        int value =
            ((payload[i + 2] & 0xff) << 24)
                | ((payload[i + 3] & 0xff) << 16)
                | ((payload[i + 4] & 0xff) << 8)
                | (payload[i + 5] & 0xff);
        applyPeerSetting(id, value);
      }
    }
    // Queue SETTINGS ACK.
    controlFrameBuffer.compact();
    Http2FrameWriter.writeSettingsAck(controlFrameBuffer);
    controlFrameBuffer.flip();
    parent.encourageWrites();
  }

  private void applyPeerSetting(int id, int value) {
    switch (id) {
      case 1 -> hpackDecoder.setMaxDynamicTableSize(value); // HEADER_TABLE_SIZE
      case 5 -> peerMaxFrameSize = value; // MAX_FRAME_SIZE
      default -> {} // other settings noted but not acted on for now
    }
  }

  // ---- HEADERS ----

  @SuppressWarnings("NullAway") // payload and connection are non-null in this context
  private void handleHeaders() throws IOException {
    int streamId = frameReader.getStreamId();
    byte[] payload = frameReader.getPayload();
    boolean endStream = frameReader.hasFlag(Http2FrameReader.FLAG_END_STREAM);

    // Decode HPACK header block.
    List<Header> headers;
    try {
      headers = hpackDecoder.decode(payload, 0, payload.length);
    } catch (HpackDecodingException e) {
      sendGoaway(ErrorCode.COMPRESSION_ERROR);
      return;
    }

    // Extract pseudo-headers and build an HttpRequest.
    String method = null;
    String path = null;
    String authority = null;
    SimpleHttpRequest.Builder builder = new SimpleHttpRequest.Builder();
    builder.setVersion(HttpVersion.HTTP_2_0);

    for (Header header : headers) {
      if (header.name().startsWith(":")) {
        switch (header.name()) {
          case ":method" -> method = header.value();
          case ":path" -> path = header.value();
          case ":authority" -> authority = header.value();
          case ":scheme" -> {} // noted but not used for routing
          default -> {} // unknown pseudo-header, ignore
        }
      } else {
        builder.addHeader(header.name(), header.value());
      }
    }

    if (method == null || path == null) {
      sendRstStream(streamId, ErrorCode.PROTOCOL_ERROR);
      return;
    }

    builder.setMethod(method);
    builder.setUri(path);
    if (authority != null && builder.getHeader(HttpHeaderName.HOST) == null) {
      builder.addHeader(HttpHeaderName.HOST, authority);
    }

    lastStreamId = Math.max(lastStreamId, streamId);

    Http2Stream.State initialState =
        endStream ? Http2Stream.State.HALF_CLOSED_REMOTE : Http2Stream.State.OPEN;
    Http2Stream stream = new Http2Stream(streamId, initialState);
    streams.put(streamId, stream);

    if (endStream) {
      // No body — dispatch immediately.
      dispatchRequest(stream, builder);
    } else {
      // Body will follow in DATA frames. Store the builder for later.
      // For simplicity, we store the partial request in the stream and dispatch on END_STREAM.
      stream.setRequestBuilder(builder);
    }
  }

  // ---- DATA ----

  private void handleData() {
    int streamId = frameReader.getStreamId();
    boolean endStream = frameReader.hasFlag(Http2FrameReader.FLAG_END_STREAM);
    byte[] payload = frameReader.getPayload();

    Http2Stream stream = streams.get(streamId);
    if (stream == null) {
      // Stream may have been reset or doesn't exist.
      return;
    }

    if (payload != null && payload.length > 0) {
      stream.appendBodyData(payload, 0, payload.length);
      // Auto-ack: send WINDOW_UPDATE for both stream and connection.
      queueWindowUpdate(streamId, payload.length);
      queueWindowUpdate(0, payload.length);
      parent.encourageWrites();
    }

    if (endStream) {
      stream.setState(Http2Stream.State.HALF_CLOSED_REMOTE);
      SimpleHttpRequest.Builder builder = stream.getRequestBuilder();
      if (builder != null) {
        dispatchRequest(stream, builder);
      }
    }
  }

  // ---- PING ----

  private void handlePing() {
    if (frameReader.hasFlag(Http2FrameReader.FLAG_ACK)) {
      return; // ignore PING ACK
    }
    byte[] payload = frameReader.getPayload();
    if (payload != null && payload.length == 8) {
      controlFrameBuffer.compact();
      Http2FrameWriter.writePing(controlFrameBuffer, payload, true);
      controlFrameBuffer.flip();
      parent.encourageWrites();
    }
  }

  // ---- WINDOW_UPDATE ----

  private void handleWindowUpdate() {
    // We don't track send windows yet (auto-ack flow control).
    // This will be needed for M5 polish.
  }

  // ---- RST_STREAM ----

  private void handleRstStream() {
    int streamId = frameReader.getStreamId();
    Http2Stream stream = streams.remove(streamId);
    if (stream != null) {
      stream.setState(Http2Stream.State.CLOSED);
    }
  }

  // ---- GOAWAY ----

  private void handleGoaway() {
    // Peer is shutting down — stop creating new streams.
    goawaySent = true;
  }

  // ---- Request dispatch ----

  @SuppressWarnings("NullAway") // connection is non-null after connect()
  private void dispatchRequest(Http2Stream stream, SimpleHttpRequest.Builder builder) {
    HttpRequest request;
    byte[] bodyBytes = stream.getBodyBytes();
    if (bodyBytes.length > 0) {
      builder.setBody(new HttpRequest.InMemoryBody(bodyBytes));
      builder.addHeader(HttpHeaderName.CONTENT_LENGTH, Integer.toString(bodyBytes.length));
    }
    try {
      request = builder.build();
    } catch (MalformedRequestException e) {
      sendErrorResponse(stream, StandardResponses.BAD_REQUEST);
      return;
    }

    RequestAction action = connectHandler.applyLocal(request);
    if (action instanceof RequestAction.ServeLocally serve) {
      HttpResponseWriter writer = new Http2ResponseWriter(stream);
      requestHandler.queueRequest(serve.handler(), connection, request, writer);
    } else if (action instanceof RequestAction.Deny deny) {
      HttpResponse denyResponse = deny.response();
      sendErrorResponse(stream, denyResponse != null ? denyResponse : StandardResponses.FORBIDDEN);
    } else {
      // Forward/ForwardAndCapture not supported over HTTP/2 yet.
      sendErrorResponse(stream, StandardResponses.NOT_IMPLEMENTED);
    }
  }

  private void sendErrorResponse(Http2Stream stream, HttpResponse errorResponse) {
    byte[] headerBlock = encodeResponseHeaders(errorResponse);
    byte[] body = errorResponse.getBody();
    stream.setResponse(headerBlock, body != null ? body : new byte[0], true);
    parent.encourageWrites();
  }

  // ---- Response encoding ----

  private byte[] encodeResponseHeaders(HttpResponse response) {
    var headerList = new java.util.ArrayList<Header>();
    headerList.add(new Header(":status", Integer.toString(response.getStatusCode())));
    for (var entry : response.getHeaders()) {
      // Skip HTTP/1.1-specific headers that don't apply to HTTP/2.
      String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
      if ("connection".equals(name) || "transfer-encoding".equals(name)) {
        continue;
      }
      headerList.add(new Header(name, entry.getValue()));
    }
    return hpackEncoder.encode(headerList.toArray(new Header[0]));
  }

  // ---- Control frame helpers ----

  private void queueWindowUpdate(int streamId, int increment) {
    controlFrameBuffer.compact();
    Http2FrameWriter.writeWindowUpdate(controlFrameBuffer, streamId, increment);
    controlFrameBuffer.flip();
  }

  private void sendGoaway(int errorCode) {
    controlFrameBuffer.compact();
    Http2FrameWriter.writeGoaway(controlFrameBuffer, lastStreamId, errorCode);
    controlFrameBuffer.flip();
    goawaySent = true;
    parent.encourageWrites();
  }

  private void sendRstStream(int streamId, int errorCode) {
    controlFrameBuffer.compact();
    Http2FrameWriter.writeRstStream(controlFrameBuffer, streamId, errorCode);
    controlFrameBuffer.flip();
    streams.remove(streamId);
    parent.encourageWrites();
  }

  private void drainControlFrames(ByteBuffer out) {
    while (controlFrameBuffer.hasRemaining() && out.hasRemaining()) {
      int toCopy = Math.min(controlFrameBuffer.remaining(), out.remaining());
      out.put(
          controlFrameBuffer.array(),
          controlFrameBuffer.arrayOffset() + controlFrameBuffer.position(),
          toCopy);
      controlFrameBuffer.position(controlFrameBuffer.position() + toCopy);
    }
  }

  // ---- Response writer ----

  /**
   * HttpResponseWriter implementation for HTTP/2. Called from the executor thread after the handler
   * completes. Encodes the response into HPACK headers + body and signals the NIO thread.
   */
  private final class Http2ResponseWriter implements HttpResponseWriter {
    private final Http2Stream stream;
    private final AtomicBoolean committed = new AtomicBoolean();

    Http2ResponseWriter(Http2Stream stream) {
      this.stream = stream;
    }

    @Override
    public void commitBuffered(HttpResponse response) throws IOException {
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException("Response already committed");
      }
      byte[] headerBlock = encodeResponseHeaders(response);
      byte[] body = response.getBody();
      if (body == null || !HttpStatusCode.mayHaveBody(response.getStatusCode())) {
        body = new byte[0];
      }
      stream.setResponse(headerBlock, body, true);
      parent.queue(() -> parent.encourageWrites());
    }

    @Override
    public java.io.OutputStream commitStreamed(HttpResponse response) throws IOException {
      // For now, buffer the streamed response and commit when closed.
      // A proper streaming implementation would use a pipe buffer (like
      // HttpResponseGeneratorStreamed).
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException("Response already committed");
      }
      byte[] headerBlock = encodeResponseHeaders(response);
      return new java.io.ByteArrayOutputStream() {
        @Override
        public void close() throws IOException {
          super.close();
          byte[] body = toByteArray();
          stream.setResponse(headerBlock, body, true);
          parent.queue(() -> parent.encourageWrites());
        }
      };
    }
  }
}

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
  private int peerInitialWindowSize = 65535; // SETTINGS_INITIAL_WINDOW_SIZE default

  // Control frame queue (accumulated during read(), drained during write()).
  // We use a simple byte buffer for queued control frames.
  private final ByteBuffer controlFrameBuffer = ByteBuffer.allocate(4096);

  // Flow control: send windows (how much DATA we may send).
  private int connectionSendWindow = 65535; // initial value per spec

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
    boolean blocked = false;
    for (var it = streams.entrySet().iterator(); it.hasNext() && !blocked; ) {
      var entry = it.next();
      Http2Stream stream = entry.getValue();
      if (stream.isResponseReady()) {
        Http2Stream.WriteResult result =
            stream.writeResponseFrames(outputBuffer, peerMaxFrameSize, connectionSendWindow);
        connectionSendWindow -= stream.getLastDataBytesSent();
        switch (result) {
          case DONE -> it.remove();
          case BLOCKED -> blocked = true;
          case WAITING -> {} // skip, try next stream
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

  private boolean consumePreface() throws IOException {
    while (inputBuffer.hasRemaining() && prefaceOffset < CLIENT_PREFACE.length) {
      byte b = inputBuffer.get();
      if (b != CLIENT_PREFACE[prefaceOffset]) {
        throw new IOException("Invalid HTTP/2 client connection preface");
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
    if (frameReader.hasFrameSizeError()) {
      int streamId = frameReader.getStreamId();
      throw new IOException(
          "h2 FRAME_SIZE_ERROR: frame type="
              + frameReader.getType()
              + " length="
              + frameReader.getLength()
              + " stream="
              + streamId);
    }
    int type = frameReader.getType();
    switch (type) {
      case FrameType.SETTINGS -> handleSettings();
      case FrameType.HEADERS -> handleHeaders();
      case FrameType.DATA -> handleData();
      case FrameType.PING -> handlePing();
      case FrameType.WINDOW_UPDATE -> handleWindowUpdate();
      case FrameType.RST_STREAM -> handleRstStream();
      case FrameType.GOAWAY -> handleGoaway();
      case FrameType.CONTINUATION ->
          throw new IOException("h2 unexpected CONTINUATION frame (not supported)");
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
      case 1 -> {} // HEADER_TABLE_SIZE: limits our encoder's dynamic table. We don't use one.
      case 4 -> { // INITIAL_WINDOW_SIZE
        int delta = value - peerInitialWindowSize;
        peerInitialWindowSize = value;
        // Adjust all existing stream send windows by the delta.
        for (Http2Stream stream : streams.values()) {
          stream.adjustSendWindow(delta);
        }
      }
      case 5 -> peerMaxFrameSize = value; // MAX_FRAME_SIZE
      default -> {}
    }
  }

  // ---- HEADERS ----

  @SuppressWarnings("NullAway") // payload and connection are non-null in this context
  private void handleHeaders() throws IOException {
    if (!frameReader.hasFlag(Http2FrameReader.FLAG_END_HEADERS)) {
      // CONTINUATION frames are not supported. Per RFC 9113 §6.2, a HEADERS frame without
      // END_HEADERS must be followed by CONTINUATION frames. Reject with a connection error.
      throw new IOException("h2 HEADERS without END_HEADERS (CONTINUATION not supported)");
    }
    int streamId = frameReader.getStreamId();
    // RFC 9113 §5.1.1: client stream IDs must be odd and greater than any previously opened.
    if (streamId % 2 == 0 || streamId <= lastStreamId) {
      throw new IOException(
          "h2 PROTOCOL_ERROR: invalid stream ID " + streamId + " (last=" + lastStreamId + ")");
    }
    byte[] payload = frameReader.getPayload();
    boolean endStream = frameReader.hasFlag(Http2FrameReader.FLAG_END_STREAM);

    // Strip padding and priority fields to find the HPACK header block.
    int hpackOffset = 0;
    int hpackLength = payload.length;
    if (frameReader.hasFlag(Http2FrameReader.FLAG_PADDED)) {
      int padLength = payload[0] & 0xff;
      hpackOffset += 1;
      hpackLength -= 1 + padLength;
    }
    if (frameReader.hasFlag(Http2FrameReader.FLAG_PRIORITY)) {
      hpackOffset += 5; // 4-byte stream dependency + 1-byte weight
      hpackLength -= 5;
    }
    if (hpackLength < 0) {
      throw new IOException("h2 HEADERS frame too short after stripping padding/priority");
    }

    // Decode HPACK header block.
    List<Header> headers;
    try {
      headers = hpackDecoder.decode(payload, hpackOffset, hpackLength);
    } catch (HpackDecodingException e) {
      throw new IOException("h2 HPACK decoding failed", e);
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
      throw new IOException(
          "h2 HEADERS missing required pseudo-headers: :method=" + method + " :path=" + path);
    }

    builder.setMethod(method);
    builder.setUri(path);
    if (authority != null && builder.getHeader(HttpHeaderName.HOST) == null) {
      builder.addHeader(HttpHeaderName.HOST, authority);
    }

    lastStreamId = Math.max(lastStreamId, streamId);

    Http2Stream.State initialState =
        endStream ? Http2Stream.State.HALF_CLOSED_REMOTE : Http2Stream.State.OPEN;
    Http2Stream stream = new Http2Stream(streamId, initialState, peerInitialWindowSize);
    streams.put(streamId, stream);

    // Route the request now (at header time) so we can check upload policy before accepting body.
    HttpRequest partialRequest;
    try {
      partialRequest = builder.buildPartialRequest();
    } catch (Exception e) {
      sendErrorResponse(stream, StandardResponses.BAD_REQUEST);
      return;
    }

    RequestAction action = connectHandler.applyLocal(partialRequest);
    if (action instanceof RequestAction.ServeLocally serve) {
      if (endStream) {
        dispatchRequest(stream, builder, serve);
      } else {
        // Check upload policy before accepting body DATA frames.
        if (!serve.uploadPolicy().isAllowed(partialRequest)) {
          sendErrorResponse(stream, StandardResponses.PAYLOAD_TOO_LARGE);
          return;
        }
        stream.setRequestBuilder(builder);
        stream.setRoutingResult(serve);
      }
    } else if (action instanceof RequestAction.Deny deny) {
      HttpResponse denyResponse = deny.response();
      sendErrorResponse(stream, denyResponse != null ? denyResponse : StandardResponses.FORBIDDEN);
    } else {
      sendErrorResponse(stream, StandardResponses.NOT_IMPLEMENTED);
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
      int dataOffset = 0;
      int dataLength = payload.length;
      if (frameReader.hasFlag(Http2FrameReader.FLAG_PADDED)) {
        int padLength = payload[0] & 0xff;
        dataOffset = 1;
        dataLength -= 1 + padLength;
      }
      if (dataLength > 0) {
        stream.appendBodyData(payload, dataOffset, dataLength);
      }
      // Auto-ack the full frame payload (including padding) per flow control.
      queueWindowUpdate(streamId, payload.length);
      queueWindowUpdate(0, payload.length);
      parent.encourageWrites();
    }

    if (endStream) {
      stream.setState(Http2Stream.State.HALF_CLOSED_REMOTE);
      SimpleHttpRequest.Builder builder = stream.getRequestBuilder();
      RequestAction.ServeLocally serve = stream.getRoutingResult();
      if (builder != null && serve != null) {
        dispatchRequest(stream, builder, serve);
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

  @SuppressWarnings("NullAway")
  private void handleWindowUpdate() {
    int streamId = frameReader.getStreamId();
    byte[] payload = frameReader.getPayload();
    if (payload == null || payload.length != 4) {
      return;
    }
    int increment =
        ((payload[0] & 0x7f) << 24)
            | ((payload[1] & 0xff) << 16)
            | ((payload[2] & 0xff) << 8)
            | (payload[3] & 0xff);
    if (increment == 0) {
      return;
    }
    if (streamId == 0) {
      connectionSendWindow += increment;
    } else {
      Http2Stream stream = streams.get(streamId);
      if (stream != null) {
        stream.adjustSendWindow(increment);
      }
    }
    // Window opened — there may be streams blocked on flow control.
    parent.encourageWrites();
  }

  // ---- RST_STREAM ----

  private void handleRstStream() {
    int streamId = frameReader.getStreamId();
    Http2Stream stream = streams.remove(streamId);
    if (stream != null) {
      stream.setState(Http2Stream.State.CLOSED);
      Http2StreamBuffer buf = stream.getStreamingBuffer();
      if (buf != null) {
        buf.cancelStream();
      }
    }
  }

  // ---- GOAWAY ----

  private void handleGoaway() {
    // Peer is shutting down — stop creating new streams.
    goawaySent = true;
  }

  // ---- Request dispatch ----

  @SuppressWarnings("NullAway") // connection is non-null after connect()
  private void dispatchRequest(
      Http2Stream stream, SimpleHttpRequest.Builder builder, RequestAction.ServeLocally serve) {
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

    HttpResponseWriter writer = new Http2ResponseWriter(stream);
    requestHandler.queueRequest(serve.handler(), connection, request, writer);
  }

  private void sendErrorResponse(Http2Stream stream, HttpResponse errorResponse) {
    byte[] body = errorResponse.getBody();
    if (body == null) {
      body = new byte[0];
    }
    byte[] headerBlock = encodeResponseHeaders(errorResponse, body.length);
    stream.setResponse(headerBlock, body, true);
    parent.encourageWrites();
  }

  // ---- Response encoding ----

  /**
   * @param contentLength if >= 0, adds a content-length header with this value
   */
  private byte[] encodeResponseHeaders(HttpResponse response, int contentLength) {
    var headerList = new java.util.ArrayList<Header>();
    headerList.add(new Header(":status", Integer.toString(response.getStatusCode())));
    for (var entry : response.getHeaders()) {
      String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
      // Skip HTTP/1.1-specific headers that don't apply to HTTP/2,
      // and skip content-length if we're setting it ourselves.
      if ("connection".equals(name)
          || "transfer-encoding".equals(name)
          || (contentLength >= 0 && "content-length".equals(name))) {
        continue;
      }
      headerList.add(new Header(name, entry.getValue()));
    }
    if (contentLength >= 0) {
      headerList.add(new Header("content-length", Integer.toString(contentLength)));
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
      byte[] body = response.getBody();
      boolean bodyAllowed = HttpStatusCode.mayHaveBody(response.getStatusCode());
      if (!bodyAllowed || body == null) {
        body = new byte[0];
      }
      byte[] headerBlock = encodeResponseHeaders(response, bodyAllowed ? body.length : -1);
      stream.setResponse(headerBlock, body, true);
      parent.queue(() -> parent.encourageWrites());
    }

    @Override
    public java.io.OutputStream commitStreamed(HttpResponse response) throws IOException {
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException("Response already committed");
      }
      byte[] headerBlock = encodeResponseHeaders(response, -1);
      Http2StreamBuffer buffer =
          new Http2StreamBuffer(65536, () -> parent.queue(() -> parent.encourageWrites()));
      stream.setStreamingResponse(headerBlock, buffer);
      parent.queue(() -> parent.encourageWrites()); // send HEADERS immediately
      return new java.io.OutputStream() {
        @Override
        public void write(int b) throws IOException {
          write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
          buffer.write(b, off, len);
        }

        @Override
        public void close() {
          buffer.close();
        }
      };
    }
  }
}

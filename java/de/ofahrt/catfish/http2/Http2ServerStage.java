package de.ofahrt.catfish.http2;

import de.ofahrt.catfish.http.CompressingResponseWriter;
import de.ofahrt.catfish.http.GzipRequestBodyDecoder;
import de.ofahrt.catfish.http.IncrementalHttpRequestParser;
import de.ofahrt.catfish.http2.Hpack.Header;
import de.ofahrt.catfish.http2.HpackDecoder.HpackDecodingException;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
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
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
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
  private final @Nullable Executor executor;
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
  // Bounded: when it fills up, we apply backpressure by pausing reads — TCP flow control
  // slows the peer to our write rate, preventing PING/SETTINGS flood DoS.
  private static final int CONTROL_FRAME_QUEUE_CAPACITY = 4096;
  private final ByteBuffer controlFrameQueue = ByteBuffer.allocate(CONTROL_FRAME_QUEUE_CAPACITY);
  // Scratch buffer for writing individual control frames before appending to the queue.
  private final ByteBuffer controlFrameScratch = ByteBuffer.allocate(64);

  // Flow control: send windows (how much DATA we may send).
  private int connectionSendWindow = 65535; // initial value per spec

  // Flow control: pending WINDOW_UPDATE bytes for the connection. Accumulated as DATA is
  // received and emitted once the accumulated count exceeds WINDOW_UPDATE_THRESHOLD, to avoid
  // amplifying small DATA frames into larger WINDOW_UPDATE replies.
  private static final int WINDOW_UPDATE_THRESHOLD = 64;

  private static final String GZIP_ENCODING = "gzip";
  private static final String X_GZIP_ENCODING = "x-gzip";
  private static final String IDENTITY_ENCODING = "identity";
  private int pendingConnAckBytes;

  // Last stream ID we processed (for GOAWAY).
  private int lastStreamId;
  private boolean goawaySent;
  private boolean goawayReceived;
  // Set by read() when the control frame queue fills; cleared by write() after drain.
  private boolean readsPausedForBackpressure;

  // Per-connection request dispatch throttling. Bounds the number of handlers running on
  // the executor for this connection at any time; additional requests wait in heldRequests.
  // Defends against Rapid Reset (CVE-2023-44487): the attacker can send HEADERS+RST_STREAM
  // fast, but only maxConcurrentDispatches handlers actually run, and new requests are only
  // released when a previous handler finishes. Each handler is charged regardless of whether
  // its response is ultimately sent, so RST_STREAM doesn't free the slot early.
  private final int maxConcurrentDispatches;
  private int inFlightRequests;
  private final ArrayDeque<HeldRequest> heldRequests = new ArrayDeque<>();

  private record HeldRequest(
      Http2Stream stream, SimpleHttpRequest.Builder builder, RequestAction.ServeLocally serve) {}

  public Http2ServerStage(
      Pipeline parent,
      RequestQueue requestHandler,
      ConnectHandler connectHandler,
      @Nullable Executor executor,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this(
        parent,
        requestHandler,
        connectHandler,
        executor,
        inputBuffer,
        outputBuffer,
        Runtime.getRuntime().availableProcessors());
  }

  public Http2ServerStage(
      Pipeline parent,
      RequestQueue requestHandler,
      ConnectHandler connectHandler,
      @Nullable Executor executor,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer,
      int maxConcurrentDispatches) {
    this.parent = parent;
    this.requestHandler = requestHandler;
    this.connectHandler = connectHandler;
    this.executor = executor;
    this.maxConcurrentDispatches = maxConcurrentDispatches;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    controlFrameQueue.flip(); // start in read mode, empty
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
      // Backpressure: if the control frame queue is nearly full (less than 64 bytes free),
      // pause reads until write() drains it. TCP flow control will slow the peer.
      // The queue is in read mode, so remaining() == queued bytes.
      if (controlFrameQueue.remaining() >= CONTROL_FRAME_QUEUE_CAPACITY - 64) {
        readsPausedForBackpressure = true;
        parent.encourageWrites();
        return ConnectionControl.PAUSE;
      }

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

    drainControlFrames(outputBuffer);
    serverPrefaceSent = true;

    // If reads were paused because the queue filled up, resume once it's drained enough.
    if (readsPausedForBackpressure
        && controlFrameQueue.remaining() < CONTROL_FRAME_QUEUE_CAPACITY / 2) {
      readsPausedForBackpressure = false;
      parent.encourageReads();
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
    if ((goawaySent || goawayReceived) && streams.isEmpty()) {
      return ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
    }
    return ConnectionControl.PAUSE;
  }

  @Override
  public void close() {
    for (Http2Stream stream : streams.values()) {
      Http2StreamBuffer buf = stream.getStreamingBuffer();
      if (buf != null) {
        buf.cancelStream();
      }
    }
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
    Http2FrameWriter.writeSettings(
        controlFrameScratch,
        Setting.MAX_CONCURRENT_STREAMS.id(),
        DEFAULT_MAX_CONCURRENT_STREAMS,
        Setting.ENABLE_PUSH.id(),
        0,
        Setting.MAX_HEADER_LIST_SIZE.id(),
        HpackDecoder.DEFAULT_MAX_HEADER_LIST_SIZE);
    flushScratch();
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

  private void handleSettings() throws IOException {
    if (frameReader.getStreamId() != 0) {
      throw new IOException(
          "h2 PROTOCOL_ERROR: SETTINGS frame on stream " + frameReader.getStreamId());
    }
    if (frameReader.hasFlag(FrameFlags.FLAG_ACK)) {
      return;
    }
    byte[] payload = frameReader.getPayload();
    if (payload.length % 6 != 0) {
      throw new IOException(
          "h2 FRAME_SIZE_ERROR: SETTINGS payload length "
              + payload.length
              + " not a multiple of 6");
    }
    for (int i = 0; i + 5 < payload.length; i += 6) {
      int id = ((payload[i] & 0xff) << 8) | (payload[i + 1] & 0xff);
      int value =
          ((payload[i + 2] & 0xff) << 24)
              | ((payload[i + 3] & 0xff) << 16)
              | ((payload[i + 4] & 0xff) << 8)
              | (payload[i + 5] & 0xff);
      applyPeerSetting(id, value);
    }
    // Queue SETTINGS ACK.
    Http2FrameWriter.writeSettingsAck(controlFrameScratch);
    flushScratch();
    parent.encourageWrites();
  }

  private void applyPeerSetting(int id, int value) throws IOException {
    Setting setting = Setting.fromId(id);
    if (setting == null) {
      return; // unknown settings are ignored per spec
    }
    switch (setting) {
      case HEADER_TABLE_SIZE -> {} // limits our encoder's dynamic table. We don't use one.
      case ENABLE_PUSH -> {
        if (value != 0 && value != 1) {
          throw new IOException(
              "h2 PROTOCOL_ERROR: SETTINGS_ENABLE_PUSH must be 0 or 1, got " + value);
        }
      }
      case INITIAL_WINDOW_SIZE -> {
        if (value < 0) { // value is parsed as signed int; > 2^31-1 appears negative
          throw new IOException(
              "h2 FLOW_CONTROL_ERROR: SETTINGS_INITIAL_WINDOW_SIZE exceeds 2^31-1");
        }
        int delta = value - peerInitialWindowSize;
        peerInitialWindowSize = value;
        for (Http2Stream stream : streams.values()) {
          if (!stream.adjustSendWindow(delta)) {
            throw new IOException("h2 FLOW_CONTROL_ERROR: stream send window overflow");
          }
        }
      }
      case MAX_FRAME_SIZE -> {
        if (value < 16384 || value > 16777215) {
          throw new IOException(
              "h2 PROTOCOL_ERROR: SETTINGS_MAX_FRAME_SIZE must be in [16384, 16777215], got "
                  + value);
        }
        peerMaxFrameSize = value;
      }
      case MAX_CONCURRENT_STREAMS, MAX_HEADER_LIST_SIZE -> {} // noted but not acted on
    }
  }

  // ---- HEADERS ----

  private void handleHeaders() throws IOException {
    if (!frameReader.hasFlag(FrameFlags.FLAG_END_HEADERS)) {
      // CONTINUATION frames are not supported. Per RFC 9113 §6.2, a HEADERS frame without
      // END_HEADERS must be followed by CONTINUATION frames. Reject with a connection error.
      throw new IOException("h2 HEADERS without END_HEADERS (CONTINUATION not supported)");
    }
    int streamId = frameReader.getStreamId();
    if (goawaySent || goawayReceived) {
      // GOAWAY in either direction — ignore new streams per RFC 9113 §6.8.
      return;
    }
    // RFC 9113 §5.1.1: client stream IDs must be odd and greater than any previously opened.
    if (streamId % 2 == 0 || streamId <= lastStreamId) {
      throw new IOException(
          "h2 PROTOCOL_ERROR: invalid stream ID " + streamId + " (last=" + lastStreamId + ")");
    }
    byte[] payload = frameReader.getPayload();
    boolean endStream = frameReader.hasFlag(FrameFlags.FLAG_END_STREAM);

    // Strip padding and priority fields to find the HPACK header block.
    int hpackOffset = 0;
    int hpackLength = payload.length;
    if (frameReader.hasFlag(FrameFlags.FLAG_PADDED)) {
      int padLength = payload[0] & 0xff;
      hpackOffset += 1;
      hpackLength -= 1 + padLength;
    }
    if (frameReader.hasFlag(FrameFlags.FLAG_PRIORITY)) {
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

    // RFC 9113 §5.1.2: streams in the open or half-closed states count toward the advertised
    // SETTINGS_MAX_CONCURRENT_STREAMS, and the streams map holds exactly those. Refuse a HEADERS
    // frame that would exceed the limit with REFUSED_STREAM (retryable) rather than opening the
    // stream, so a peer that ignores the advertised limit can't grow the map — or heldRequests,
    // whose live entries are a subset of open streams — without bound. HPACK is decoded first so
    // the connection-global dynamic table stays in sync whether or not the stream is refused.
    if (streams.size() >= DEFAULT_MAX_CONCURRENT_STREAMS) {
      lastStreamId = Math.max(lastStreamId, streamId);
      queueRstStream(streamId, ErrorCode.REFUSED_STREAM);
      parent.encourageWrites();
      return;
    }

    // Validate request header fields (RFC 9113 §8.2.1). A malformed request is a stream error
    // (§8.1.1): reject with RST_STREAM(PROTOCOL_ERROR) so the connection and other streams survive.
    // Validation runs after the full HPACK decode above, so the connection-global dynamic table is
    // advanced over the whole block regardless of outcome and a rejected stream cannot desync later
    // streams. Rejecting here, before the request is built, also keeps malformed field names/values
    // (e.g. CR/LF in a value) out of the HTTP/1.1 reverse-proxy / FastCGI forwarders, where they
    // would be an h2->h1 header-injection / request-smuggling vector.
    if (!validateRequestHeaderBlock(headers)
        || !validateRequestPseudoHeaders(headers)
        || !validateNoConnectionSpecificFields(headers)) {
      lastStreamId = Math.max(lastStreamId, streamId);
      queueRstStream(streamId, ErrorCode.PROTOCOL_ERROR);
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
    HttpRequest partialRequest = builder.buildPartialRequest();

    if (executor != null) {
      boolean finalEndStream = endStream;
      executor.execute(
          () -> {
            RequestAction action;
            try {
              action = connectHandler.applyLocal(partialRequest);
            } catch (RuntimeException | Error e) {
              parent.queue(
                  () -> {
                    throw e;
                  });
              return;
            }
            parent.queue(
                () -> applyRoutingResult(stream, builder, partialRequest, action, finalEndStream));
          });
    } else {
      RequestAction action = connectHandler.applyLocal(partialRequest);
      applyRoutingResult(stream, builder, partialRequest, action, endStream);
    }
  }

  /** Applies a routing decision. Called on the NIO thread (either inline or via parent.queue). */
  private void applyRoutingResult(
      Http2Stream stream,
      SimpleHttpRequest.Builder builder,
      HttpRequest partialRequest,
      RequestAction action,
      boolean endStream) {
    if (action instanceof RequestAction.ServeLocally serve) {
      // Content-coding parity with the HTTP/1.1 path: accept gzip/x-gzip (decoded at dispatch) and
      // identity; reject any other codec with 415 before accepting a body.
      String contentEncoding = partialRequest.getHeaders().get(HttpHeaderName.CONTENT_ENCODING);
      if (contentEncoding != null) {
        String encoding = contentEncoding.trim();
        if (encoding.equalsIgnoreCase(GZIP_ENCODING)
            || encoding.equalsIgnoreCase(X_GZIP_ENCODING)) {
          stream.setDecodeGzip();
        } else if (!encoding.equalsIgnoreCase(IDENTITY_ENCODING)) {
          stream.rejectBody();
          // Advertise what we accept: gzip (decoded) and identity.
          sendErrorResponse(stream, StandardResponses.unsupportedMediaType("gzip", "identity"));
          return;
        }
      }
      if (endStream) {
        dispatchRequest(stream, builder, serve);
      } else {
        // A body is present (this is the non-END_STREAM path). A zero ceiling forbids it outright;
        // a declared Content-Length already over the ceiling is rejected here. A body without
        // Content-Length is bounded incrementally once DATA-frame streaming enforces the ceiling
        // (spec 0002 PR 2).
        long maxBody = serve.uploadPolicy().maxDecodedBytes(partialRequest);
        String contentLength = partialRequest.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
        // For gzip the Content-Length is the compressed size, so the decoded ceiling is enforced
        // during inflation at dispatch, not here.
        if (maxBody == 0L
            || (!stream.isDecodeGzip()
                && contentLength != null
                && Long.parseLong(contentLength) > maxBody)) {
          stream.rejectBody();
          sendErrorResponse(stream, StandardResponses.PAYLOAD_TOO_LARGE);
          return;
        }
        // Enforce the ceiling on every subsequent DATA frame, and catch any body that already
        // streamed in (bounded by flow control) before this async routing decision completed.
        stream.setMaxBodyBytes(maxBody);
        if (!stream.isBodyWithinLimit()) {
          stream.rejectBody();
          sendErrorResponse(stream, StandardResponses.PAYLOAD_TOO_LARGE);
          return;
        }
        stream.setRequestBuilder(builder);
        stream.setRoutingResult(serve);
        // Routing runs asynchronously on the executor thread, so the body's END_STREAM DATA frame
        // may already have been processed by handleData() before this queued task runs. In that
        // case handleData() saw a null builder/routingResult and skipped dispatch, so we must
        // dispatch now. When END_STREAM has not yet arrived, the stream is still OPEN and a later
        // handleData() will dispatch once the builder/routingResult we just stored are visible.
        if (stream.getState() == Http2Stream.State.HALF_CLOSED_REMOTE) {
          dispatchRequest(stream, builder, serve);
        }
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
    boolean endStream = frameReader.hasFlag(FrameFlags.FLAG_END_STREAM);
    byte[] payload = frameReader.getPayload();

    // RFC 9113 §6.9.1: every DATA frame counts against the connection-level flow-control window,
    // even one we drop (unknown/closed stream) or reject (§5.1 STREAM_CLOSED). This credit is the
    // connection window's only replenishment, so it MUST run for every frame, before any early
    // return. Otherwise a well-behaved client that keeps uploading after we closed a stream — e.g.
    // we responded 401/413/redirect before reading the whole body — never gets those bytes credited
    // back; the window (shared by all streams) drains to zero and every stream on the connection
    // stalls. The full frame payload, including padding, is flow-controlled.
    creditConnectionWindow(payload.length);

    Http2Stream stream = streams.get(streamId);
    if (stream == null) {
      // Unknown or already-closed stream: drop the DATA. The connection window was credited above.
      return;
    }

    // RFC 9113 §5.1: DATA received after the client half-closed the stream with END_STREAM is a
    // STREAM_CLOSED stream error. Streams in the map are OPEN or half-closed(remote), so a non-OPEN
    // stream here means DATA arrived after END_STREAM. Rejecting it prevents the bytes from being
    // appended to the already-completed request's body buffer (unbounded growth — a GET's ceiling
    // is still Long.MAX_VALUE) and a second END_STREAM from re-dispatching the request.
    if (stream.getState() != Http2Stream.State.OPEN) {
      resetStream(streamId, stream, ErrorCode.STREAM_CLOSED);
      return;
    }

    if (payload.length > 0) {
      int dataOffset = 0;
      int dataLength = payload.length;
      if (frameReader.hasFlag(FrameFlags.FLAG_PADDED)) {
        int padLength = payload[0] & 0xff;
        dataOffset = 1;
        dataLength -= 1 + padLength;
      }
      if (dataLength > 0) {
        // Enforce the decoded-body ceiling incrementally (spec 0002). On the first frame that
        // exceeds it, respond 413 once; appendBodyData discards this and all later frames.
        if (!stream.appendBodyData(payload, dataOffset, dataLength) && !stream.isResponseReady()) {
          sendErrorResponse(stream, StandardResponses.PAYLOAD_TOO_LARGE);
        }
      }
      // Stream-level flow control (full frame payload, including padding). Emit past the threshold
      // to avoid amplifying small DATA frames into larger replies. The connection-level window is
      // already credited above.
      stream.addPendingAckBytes(payload.length);
      if (stream.getPendingAckBytes() >= WINDOW_UPDATE_THRESHOLD) {
        queueWindowUpdate(streamId, stream.takePendingAckBytes());
        parent.encourageWrites();
      }
    }

    if (endStream) {
      stream.setState(Http2Stream.State.HALF_CLOSED_REMOTE);
      // Per RFC 9113 §6.9, stream-level WINDOW_UPDATE can be skipped for closing streams.
      stream.takePendingAckBytes();
      SimpleHttpRequest.Builder builder = stream.getRequestBuilder();
      RequestAction.ServeLocally serve = stream.getRoutingResult();
      // A body that exceeded the ceiling was rejected with 413 already; do not dispatch it.
      if (builder != null && serve != null && !stream.isBodyRejected()) {
        dispatchRequest(stream, builder, serve);
      }
    }
  }

  /**
   * Credits the connection-level flow-control window for a received DATA frame of {@code
   * frameLength} bytes (the full payload, including padding), emitting a connection WINDOW_UPDATE
   * once the accumulated credit passes the threshold. Must be called for every DATA frame received
   * — including frames we drop or reject — so the window (shared by all streams) is never drained
   * by bytes we chose not to consume (RFC 9113 §6.9.1). NIO thread only.
   */
  private void creditConnectionWindow(int frameLength) {
    pendingConnAckBytes += frameLength;
    if (pendingConnAckBytes >= WINDOW_UPDATE_THRESHOLD) {
      queueWindowUpdate(0, pendingConnAckBytes);
      pendingConnAckBytes = 0;
      parent.encourageWrites();
    }
  }

  // ---- PING ----

  private void handlePing() throws IOException {
    if (frameReader.getStreamId() != 0) {
      throw new IOException("h2 PROTOCOL_ERROR: PING on stream " + frameReader.getStreamId());
    }
    if (frameReader.getLength() != 8) {
      throw new IOException("h2 FRAME_SIZE_ERROR: PING payload length " + frameReader.getLength());
    }
    if (frameReader.hasFlag(FrameFlags.FLAG_ACK)) {
      return;
    }
    byte[] payload = frameReader.getPayload();
    Http2FrameWriter.writePing(controlFrameScratch, payload, true);
    flushScratch();
    parent.encourageWrites();
  }

  // ---- WINDOW_UPDATE ----

  private void handleWindowUpdate() throws IOException {
    if (frameReader.getLength() != 4) {
      throw new IOException(
          "h2 FRAME_SIZE_ERROR: WINDOW_UPDATE payload length " + frameReader.getLength());
    }
    int streamId = frameReader.getStreamId();
    byte[] payload = frameReader.getPayload();
    int increment =
        ((payload[0] & 0x7f) << 24)
            | ((payload[1] & 0xff) << 16)
            | ((payload[2] & 0xff) << 8)
            | (payload[3] & 0xff);
    if (increment == 0) {
      throw new IOException(
          "h2 PROTOCOL_ERROR: WINDOW_UPDATE increment is 0 on stream " + streamId);
    }
    if (streamId == 0) {
      long newWindow = (long) connectionSendWindow + increment;
      if (newWindow > Integer.MAX_VALUE) {
        throw new IOException(
            "h2 FLOW_CONTROL_ERROR: connection send window overflow ("
                + connectionSendWindow
                + " + "
                + increment
                + ")");
      }
      connectionSendWindow = (int) newWindow;
    } else {
      Http2Stream stream = streams.get(streamId);
      if (stream != null && !stream.adjustSendWindow(increment)) {
        // RFC 9113 §6.9.1: stream-level flow control overflow is a stream error.
        resetStream(streamId, stream, ErrorCode.FLOW_CONTROL_ERROR);
        return;
      }
    }
    // Window opened — there may be streams blocked on flow control.
    parent.encourageWrites();
  }

  // ---- RST_STREAM ----

  private void handleRstStream() {
    int streamId = frameReader.getStreamId();
    Http2Stream stream = streams.get(streamId);
    if (stream != null) {
      // The peer already reset the stream, so we only clean up — no RST_STREAM is sent back.
      discardStream(streamId, stream);
    }
  }

  /**
   * Removes a stream and releases its resources: drops it from the stream map, closes it, cancels
   * any streaming response buffer, and drops a queued (held) request for it. Does not send
   * RST_STREAM. A dispatched handler keeps running — its slot is only freed when the handler
   * completes, not here (Rapid Reset defense). NIO thread only.
   */
  private void discardStream(int streamId, Http2Stream stream) {
    streams.remove(streamId);
    stream.setState(Http2Stream.State.CLOSED);
    Http2StreamBuffer buf = stream.getStreamingBuffer();
    if (buf != null) {
      buf.cancelStream();
    }
    heldRequests.removeIf(h -> h.stream().getStreamId() == streamId);
  }

  /**
   * Aborts a stream with a locally-initiated stream error: cleans up its state (see {@link
   * #discardStream}) and queues an RST_STREAM frame carrying {@code errorCode}. NIO thread only.
   */
  private void resetStream(int streamId, Http2Stream stream, int errorCode) {
    discardStream(streamId, stream);
    queueRstStream(streamId, errorCode);
    parent.encourageWrites();
  }

  // ---- GOAWAY ----

  private void handleGoaway() {
    goawayReceived = true;
  }

  // ---- Request dispatch ----

  @SuppressWarnings("NullAway") // connection is non-null after connect()
  private void dispatchRequest(
      Http2Stream stream, SimpleHttpRequest.Builder builder, RequestAction.ServeLocally serve) {
    if (inFlightRequests >= maxConcurrentDispatches) {
      heldRequests.add(new HeldRequest(stream, builder, serve));
      return;
    }
    doDispatch(stream, builder, serve);
  }

  @SuppressWarnings("NullAway") // connection is non-null after connect()
  private void doDispatch(
      Http2Stream stream, SimpleHttpRequest.Builder builder, RequestAction.ServeLocally serve) {
    HttpRequest request;
    byte[] bodyBytes = stream.getBodyBytes();
    if (stream.isDecodeGzip() && bodyBytes.length > 0) {
      // Content-decode (gunzip) the assembled body, bounded by the decoded ceiling enforced during
      // inflation (parity with the HTTP/1.1 path, spec 0002 PR 3).
      try {
        bodyBytes = GzipRequestBodyDecoder.decode(bodyBytes, stream.getMaxBodyBytes());
      } catch (GzipRequestBodyDecoder.BodyTooLargeException e) {
        sendErrorResponse(stream, StandardResponses.PAYLOAD_TOO_LARGE);
        return;
      } catch (GzipRequestBodyDecoder.MalformedBodyException e) {
        sendErrorResponse(stream, StandardResponses.BAD_REQUEST);
        return;
      }
    }
    try {
      if (bodyBytes.length > 0) {
        builder.setBody(new HttpRequest.InMemoryBody(bodyBytes));
        // Most clients send an explicit content-length in the HEADERS frame; content-length is not
        // a list-valued field, so adding a second occurrence would make the request malformed. Only
        // synthesize one when the client omitted it (e.g. a body sent without content-length).
        if (builder.getHeader(HttpHeaderName.CONTENT_LENGTH) == null) {
          builder.addHeader(HttpHeaderName.CONTENT_LENGTH, Integer.toString(bodyBytes.length));
        }
      }
      request = builder.build();
    } catch (MalformedRequestException e) {
      sendErrorResponse(stream, StandardResponses.BAD_REQUEST);
      return;
    }
    if (stream.isDecodeGzip()) {
      // The handler sees decoded bytes: strip Content-Encoding and, when present, correct the
      // Content-Length (which was the compressed size) to the decoded length.
      request = request.withoutHeader(HttpHeaderName.CONTENT_ENCODING);
      if (bodyBytes.length > 0) {
        request =
            request.withHeaderOverrides(
                HttpHeaders.of(HttpHeaderName.CONTENT_LENGTH, Integer.toString(bodyBytes.length)));
      }
    }

    inFlightRequests++;
    AtomicBoolean completed = new AtomicBoolean();
    Runnable notify =
        () -> {
          if (completed.compareAndSet(false, true)) {
            parent.queue(this::onRequestComplete);
          }
        };
    // Completion fires when commitBuffered returns, or when the commitStreamed OutputStream
    // is closed — not when the handler returns, since the handler may keep writing async. The
    // compressing writer sits inside NotifyingWriter so completion fires after the (gzipped)
    // response is fully written, matching the HTTP/1.1 path (spec 0006).
    HttpResponseWriter writer =
        new NotifyingWriter(
            new CompressingResponseWriter(
                new Http2ResponseWriter(stream), request, serve.compressionPolicy()),
            notify);
    requestHandler.queueRequest(serve.handler(), connection, request, writer);
  }

  /**
   * Called on the NIO thread when a dispatched request finishes. Decrements the in-flight counter
   * and dispatches the next held request, if any.
   */
  private void onRequestComplete() {
    inFlightRequests--;
    while (!heldRequests.isEmpty() && inFlightRequests < maxConcurrentDispatches) {
      HeldRequest next = heldRequests.poll();
      // Skip if the stream was RST_STREAMed while held.
      if (streams.containsKey(next.stream().getStreamId())) {
        doDispatch(next.stream(), next.builder(), next.serve());
      }
    }
  }

  /**
   * Wraps a response writer to notify completion: after commitBuffered returns, or after the
   * OutputStream from commitStreamed is closed. Handler return does NOT trigger completion — the
   * handler may return while continuing to write asynchronously.
   */
  private static final class NotifyingWriter implements HttpResponseWriter {
    private final HttpResponseWriter delegate;
    private final Runnable onComplete;

    NotifyingWriter(HttpResponseWriter delegate, Runnable onComplete) {
      this.delegate = delegate;
      this.onComplete = onComplete;
    }

    @Override
    public void commitBuffered(HttpResponse response) throws IOException {
      try {
        delegate.commitBuffered(response);
      } finally {
        onComplete.run();
      }
    }

    @Override
    public OutputStream commitStreamed(HttpResponse response) throws IOException {
      OutputStream inner = delegate.commitStreamed(response);
      return new FilterOutputStream(inner) {
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
          inner.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
          try {
            inner.close();
          } finally {
            onComplete.run();
          }
        }
      };
    }

    @Override
    public void abort() {
      delegate.abort();
      onComplete.run();
    }
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
    var headerList = new ArrayList<Header>();
    headerList.add(new Header(":status", Integer.toString(response.getStatusCode())));
    for (var entry : response.getHeaders()) {
      String name = entry.getKey();
      // Skip HTTP/1.1-specific headers that don't apply to HTTP/2,
      // and skip content-length if we're setting it ourselves.
      if (HttpHeaderName.CONNECTION.equals(name)
          || HttpHeaderName.TRANSFER_ENCODING.equals(name)
          || (contentLength >= 0 && HttpHeaderName.CONTENT_LENGTH.equals(name))) {
        continue;
      }
      // HTTP/2 requires lowercase header names (RFC 9113 §8.2).
      headerList.add(new Header(name.toLowerCase(Locale.ROOT), entry.getValue()));
    }
    if (contentLength >= 0) {
      headerList.add(new Header("content-length", Integer.toString(contentLength)));
    }
    return hpackEncoder.encode(headerList.toArray(new Header[0]));
  }

  // ---- Control frame helpers ----

  /**
   * Writes the scratch buffer contents to the control frame queue. The queue is a fixed-size
   * buffer; if it is full, read() will return PAUSE and TCP flow control will slow the peer.
   */
  private void flushScratch() {
    controlFrameScratch.flip();
    controlFrameQueue.compact();
    controlFrameQueue.put(controlFrameScratch);
    controlFrameQueue.flip();
    controlFrameScratch.clear();
  }

  private void queueWindowUpdate(int streamId, int increment) {
    Http2FrameWriter.writeWindowUpdate(controlFrameScratch, streamId, increment);
    flushScratch();
  }

  /**
   * Validates the field names and values of a decoded HTTP/2 request header block per RFC 9113
   * §8.2.1. Returns {@code true} if every field is well-formed, {@code false} if the request is
   * malformed (§8.1.1) and must be rejected as a stream error. Pseudo-header ordering/uniqueness
   * (§8.3) is checked by {@link #validateRequestPseudoHeaders} and the connection-specific field
   * rules (§8.2.2) by {@link #validateNoConnectionSpecificFields}; this pass covers name/value
   * syntax.
   */
  private static boolean validateRequestHeaderBlock(List<Header> headers) {
    for (Header header : headers) {
      if (!isValidRequestFieldName(header.name()) || !isValidRequestFieldValue(header.value())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Validates the pseudo-header structure of a decoded HTTP/2 request header block per RFC 9113
   * §8.3: every pseudo-header must precede all regular fields, each defined request pseudo-header
   * ({@code :method}, {@code :path}, {@code :scheme}, {@code :authority}) may appear at most once,
   * and any other pseudo-header (undefined, or a response pseudo-header such as {@code :status}) is
   * malformed. Returns {@code false} if the request is malformed and must be rejected as a stream
   * error. Presence of the mandatory pseudo-headers is enforced downstream when the request is
   * built.
   */
  private static boolean validateRequestPseudoHeaders(List<Header> headers) {
    boolean regularSeen = false;
    int seenMask = 0;
    for (Header header : headers) {
      String name = header.name();
      if (!name.isEmpty() && name.charAt(0) == ':') {
        if (regularSeen) {
          return false; // pseudo-header after a regular field
        }
        int bit = requestPseudoHeaderBit(name);
        if (bit == 0) {
          return false; // undefined or response-only pseudo-header in a request
        }
        if ((seenMask & bit) != 0) {
          return false; // duplicate pseudo-header
        }
        seenMask |= bit;
      } else {
        regularSeen = true;
      }
    }
    return true;
  }

  private static int requestPseudoHeaderBit(String name) {
    switch (name) {
      case ":method":
        return 1;
      case ":path":
        return 2;
      case ":scheme":
        return 4;
      case ":authority":
        return 8;
      default:
        return 0;
    }
  }

  /**
   * Rejects requests carrying connection-specific header fields, forbidden in HTTP/2 (RFC 9113
   * §8.2.2): {@code connection}, {@code keep-alive}, {@code proxy-connection}, {@code
   * transfer-encoding}, {@code upgrade}, and {@code te} with any value other than {@code trailers}.
   * Returns {@code false} if the request is malformed and must be rejected as a stream error. Field
   * names are already known to be lowercase (otherwise {@link #validateRequestHeaderBlock} rejected
   * them).
   */
  private static boolean validateNoConnectionSpecificFields(List<Header> headers) {
    for (Header header : headers) {
      switch (header.name()) {
        case "connection":
        case "keep-alive":
        case "proxy-connection":
        case "transfer-encoding":
        case "upgrade":
          return false;
        case "te":
          if (!header.value().equalsIgnoreCase("trailers")) {
            return false;
          }
          break;
        default:
          break;
      }
    }
    return true;
  }

  /** Only 101 Switching Protocols is invalid as an HTTP/2 response status (RFC 9113 §8.1). */
  private static boolean isInvalidHttp2Status(int statusCode) {
    return statusCode == HttpStatusCode.SWITCHING_PROTOCOLS.getStatusCode();
  }

  /**
   * A field name must be a non-empty lowercase RFC 7230 token — no uppercase, SP, control, or other
   * non-{@code tchar} character, and no interior {@code ':'}. A leading {@code ':'} marks a
   * pseudo-header and is allowed; the remainder is still validated as a token. Reuses the HTTP/1.1
   * {@code tchar} definition so the two parsers cannot drift.
   */
  private static boolean isValidRequestFieldName(String name) {
    if (name.isEmpty()) {
      return false;
    }
    int start = name.charAt(0) == ':' ? 1 : 0;
    if (start == name.length()) {
      return false; // a bare ":" is not a valid pseudo-header name
    }
    for (int i = start; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c > 0x7f || (c >= 'A' && c <= 'Z')) {
        return false; // non-ASCII or uppercase
      }
      if (!IncrementalHttpRequestParser.isTokenCharacter(c)) {
        return false; // SP, control, ':', and other non-tchar characters
      }
    }
    return true;
  }

  /**
   * A field value must contain no {@code NUL}, {@code CR}, or {@code LF} and must not start or end
   * with a space or horizontal tab (RFC 9113 §8.2.1). Other octets, including non-ASCII, are
   * permitted as opaque field content.
   */
  private static boolean isValidRequestFieldValue(String value) {
    if (!value.isEmpty()) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if (first == ' ' || first == '\t' || last == ' ' || last == '\t') {
        return false;
      }
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\0' || c == '\r' || c == '\n') {
        return false;
      }
    }
    return true;
  }

  private void queueRstStream(int streamId, int errorCode) {
    Http2FrameWriter.writeRstStream(controlFrameScratch, streamId, errorCode);
    flushScratch();
  }

  private void drainControlFrames(ByteBuffer out) {
    while (controlFrameQueue.hasRemaining() && out.hasRemaining()) {
      int toCopy = Math.min(controlFrameQueue.remaining(), out.remaining());
      out.put(
          controlFrameQueue.array(),
          controlFrameQueue.arrayOffset() + controlFrameQueue.position(),
          toCopy);
      controlFrameQueue.position(controlFrameQueue.position() + toCopy);
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
      if (isInvalidHttp2Status(response.getStatusCode())) {
        sendErrorResponse(stream, StandardResponses.INTERNAL_SERVER_ERROR);
        return;
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
    public OutputStream commitStreamed(HttpResponse response) throws IOException {
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException("Response already committed");
      }
      if (isInvalidHttp2Status(response.getStatusCode())) {
        sendErrorResponse(stream, StandardResponses.INTERNAL_SERVER_ERROR);
        return OutputStream.nullOutputStream();
      }
      byte[] headerBlock = encodeResponseHeaders(response, -1);
      Http2StreamBuffer buffer =
          new Http2StreamBuffer(65536, () -> parent.queue(() -> parent.encourageWrites()));
      stream.setStreamingResponse(headerBlock, buffer);
      parent.queue(() -> parent.encourageWrites()); // send HEADERS immediately
      return new OutputStream() {
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

    @Override
    public void abort() {
      if (!committed.compareAndSet(false, true)) {
        // Already committed — force close the streaming buffer if active.
        Http2StreamBuffer buf = stream.getStreamingBuffer();
        if (buf != null) {
          buf.cancelStream();
        }
        return;
      }
      // Not committed yet — send 500 Internal Server Error.
      sendErrorResponse(stream, StandardResponses.INTERNAL_SERVER_ERROR);
    }
  }
}

package de.ofahrt.catfish.http;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import org.jspecify.annotations.Nullable;

/**
 * Typed next-stage API for HTTP request input. Receives parsed HTTP request headers and raw body
 * bytes. The response is produced via an {@link HttpResponseGeneratorInstaller} passed at
 * construction time — the stage installs an {@link HttpResponseGenerator} when one becomes
 * available (synchronously in {@code onHeaders}, after body completion, or asynchronously from an
 * executor thread).
 *
 * <p>All methods on this interface are called on the NIO thread. Implementations that need to
 * perform blocking work (e.g., dispatching to an {@code HttpHandler} on an executor thread) should
 * queue the work and return immediately.
 */
public interface HttpRequestStage {

  /** Callback by which an {@link HttpRequestStage} hands its response generator to the driver. */
  @FunctionalInterface
  interface HttpResponseGeneratorInstaller {
    /** Called from the NIO thread (or queued onto it) when a response generator is ready. */
    void install(HttpResponseGenerator generator);
  }

  /**
   * Called when request headers are complete. Returns {@code null} to accept the request (body will
   * be streamed via {@link #onBodyData}/{@link #onBodyComplete}), or a non-null {@link
   * HttpResponse} to reject it — the caller sends that response and closes the handler. If the
   * request has no body and the handler accepts, {@link #onBodyComplete} is called immediately
   * after this method (without any {@link #onBodyData} calls).
   */
  @Nullable HttpResponse onHeaders(HttpRequest headers);

  /**
   * Called with raw request body bytes. For chunked transfer encoding, chunk framing is preserved
   * (not dechunked); for {@code Content-Length}, bytes are passed as received. Only called if
   * {@link #onHeaders} returned {@code null} (accept). Returns the number of bytes actually
   * consumed (written to a pipe, buffered, etc.). If fewer than {@code length} bytes are consumed,
   * the caller pauses and retries later (backpressure).
   */
  int onBodyData(byte[] data, int offset, int length);

  /**
   * Called when the request body is complete, or immediately after {@link #onHeaders} if there is
   * no body.
   */
  void onBodyComplete();

  /** Called when the connection is closing. Clean up any resources held by this handler. */
  void close();
}

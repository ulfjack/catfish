---
id: 0004
title: Reverse-proxy an upstream over a unix domain socket
status: implemented
owner: Ulf Adams
architecture_refs:
  - Proxy (ProxyRequestStage, OriginForwarder)
  - Routing (RequestAction, HttpServerStage)
---

# 0004 — Reverse-proxy an upstream over a unix domain socket

## Summary

Let a reverse proxy forward a request to a backend listening on a **unix domain socket** instead of
a TCP `host:port`. Add one additive `RequestAction` variant — `forwardToUnixSocket(Path, request)` —
and teach the outbound dial (`OriginForwarder`) to connect over a `SocketChannel` of family `UNIX`.
The destination socket path comes **only** from application code (the `ConnectHandler`), never from
the request URI or `Host` header. Existing TCP/TLS forwarding is byte-for-byte unchanged.

## Goals

- An application can return `RequestAction.forwardToUnixSocket(path, request)` from
  `ConnectHandler.applyLocal` / `applyProxy` and have the request forwarded to a backend listening on
  the unix socket at `path`, with request/response bodies streamed exactly as for a TCP upstream.
- The full existing forwarding behaviour (relative-URI rewrite, hop-by-hop header stripping,
  chunked / content-length / until-EOF response body handling, `502` on dial failure) applies
  unchanged to the unix path — it is transport-agnostic once the streams are obtained.
- The change is **non-breaking**: the current TCP `Forward` / `ForwardAndCapture` code paths and the
  `OriginForwarder` behaviour they exercise are preserved.

## Non-Goals

- **TLS over a unix socket.** A unix upstream is local; the connection is plaintext HTTP/1.1. There
  is no `useTls` / SNI / `startHandshake` for the unix transport.
- **Inferring a unix destination from the request.** No `unix:` URI scheme, no `Host`-header socket
  path. The destination is always supplied out-of-band by application code (see Security).
- **Response capture (`ForwardAndCapture`) to a unix socket** in this spec. The dialer refactor makes
  it a trivial follow-up, but the first cut ships only `forwardToUnixSocket` (no capture variant).
- **Connection pooling / keep-alive to the upstream.** Unchanged: one connection per proxied request,
  closed after — same as the TCP path today.
- HTTP/2 to the upstream. The upstream request is HTTP/1.1, as today.

## Background / Context

The reverse-proxy forward path is:
`HttpServerStage.applyRoutingDecision` → builds a `ProxyRequestStage` → spawns an `OriginForwarder`
on the executor thread → `OriginForwarder.runForwardToOrigin` dials the upstream and pumps bytes.

The destination is modelled today as a TCP triple. `HttpServerStage.parseOrigin`
(`HttpServerStage.java:668`) derives `record Origin(String host, int port, boolean useTls)` from the
request URI or `Host` header, and the dispatch (`HttpServerStage.java:348-391`) passes
`(host, port, useTls, SocketFactory)` into `ProxyRequestStage`, which passes them into
`OriginForwarder`. The dial itself (`OriginForwarder.java:149-169`) is:

```java
Socket socket = socketFactory.createSocket(originHost, originPort);
if (useTls && socket instanceof SSLSocket sslSocket) { /* SNI + startHandshake */ }
...
OutputStream originOut = socket.getOutputStream();   // line 168
InputStream  originIn  = socket.getInputStream();    // line 191
...
socket.close();                                      // lines 281, 285
```

Everything after the dial operates on `InputStream` / `OutputStream` — the header write
(`requestHeadersToBytes`), the request-body pump from the `PipeBuffer`, the incremental response
parse, and the response-body streaming are all transport-agnostic.

Catfish already dials a unix socket outbound in exactly one place — FastCGI — and that is the
pattern to mirror. `FastCgiConnection.connectUnix` (`fastcgi/FastCgiConnection.java:39`):

```java
SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
channel.connect(UnixDomainSocketAddress.of(path));
// streams via Channels.newInputStream(channel) / Channels.newOutputStream(channel)
```

Its doc comment notes the relevant constraint: a unix `SocketChannel` in blocking mode exposes I/O
via `Channels.newInputStream/newOutputStream` and **does not support socket read timeouts** — the
same as the TCP path here, which never sets `SO_TIMEOUT` either.

Unix sockets also already exist on the **inbound** side (`Binding.UnixSocket`,
`HttpEndpoint.onUnixSocket`, `NetworkEngine.listenUnixSocket`) — but nothing on the reverse-proxy
**outbound** side. The obstacle is purely the `(host, port, SocketFactory)` shape of the dial: a
unix socket has no host:port and `SocketFactory.createSocket(host, port)` cannot produce one.

The ALPN work (spec 0001) is entirely inbound and orthogonal; the upstream request is always
HTTP/1.1 regardless of the negotiated client protocol, so this change does not interact with it.

## Design

### 1. Introduce an internal outbound dialer

Add a small internal abstraction that encapsulates "connect to the upstream and hand back streams",
replacing the `(host, port, useTls, SocketFactory)` tuple that `ProxyRequestStage` and
`OriginForwarder` currently thread through. Package-private, in `de.ofahrt.catfish`:

```java
interface OriginDialer {
  /** Blocking connect (runs on the executor thread). Throws IOException on failure. */
  OriginConnection connect() throws IOException;

  /** Human-readable target for logging / error context, e.g. "host:port" or "unix:/path". */
  String describe();
}

/** A connected upstream: streams plus the underlying resource to close. */
final class OriginConnection implements Closeable {
  final InputStream in;
  final OutputStream out;
  private final Closeable underlying;
  // close() closes `underlying`.
}
```

Two implementations:

- **`TcpOriginDialer`** — wraps the existing logic verbatim: `socketFactory.createSocket(host,
  port)`, the `useTls` SNI + `startHandshake` block, and returns an `OriginConnection` over
  `socket.getInputStream()/getOutputStream()` whose `close()` closes the `Socket`. This is a pure
  extraction of today's `OriginForwarder.runForwardToOrigin` dial prologue — behaviour-preserving.
- **`UnixOriginDialer`** — `SocketChannel.open(StandardProtocolFamily.UNIX)`,
  `channel.connect(UnixDomainSocketAddress.of(path))`, streams via
  `Channels.newInputStream(channel)` / `Channels.newOutputStream(channel)`, `close()` closes the
  channel. Mirrors `FastCgiConnection.connectUnix`, including the `boolean ok` / `finally`
  close-on-failure guard so a failed connect never leaks a channel.

`OriginForwarder` drops its `originHost` / `originPort` / `useTls` / `socketFactory` fields and holds
a single `OriginDialer`. Its dial site becomes:

```java
OriginConnection conn;
try {
  conn = dialer.connect();
} catch (IOException e) {
  drainAndClosePipe();
  sendErrorResponse();          // existing 502 path, unchanged
  closeCaptureStream(captureStream);
  return;
}
try (conn) {
  OutputStream originOut = conn.out;
  InputStream  originIn  = conn.in;
  ... // the rest of runForwardToOrigin is byte-for-byte unchanged
}
```

The two explicit `socket.close()` calls (lines 281, 285) are replaced by the `try (conn)` /
existing close handling. SNI and `startHandshake` move **into** `TcpOriginDialer` (they only ever
applied to TLS TCP), so `OriginForwarder` no longer references `SSLSocket` / `SSLParameters`.

### 2. Thread the dialer through `ProxyRequestStage`

`ProxyRequestStage`'s `(String host, int port, boolean useTls, SocketFactory socketFactory)`
constructor params collapse to a single `OriginDialer dialer`, which it forwards to `OriginForwarder`
unchanged in `onHeaders`. No other `ProxyRequestStage` logic changes.

### 3. New `RequestAction` variant (public API)

In `de.ofahrt.catfish.model.server.RequestAction` (a sealed interface), add one record variant and
one static factory — purely additive:

```java
/** Forward the request to a backend listening on the given unix domain socket. Body is streamed. */
record ForwardToUnixSocket(HttpRequest request, Path socketPath) implements RequestAction {
  public ForwardToUnixSocket {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(socketPath, "socketPath");
  }
}

static RequestAction forwardToUnixSocket(Path socketPath, HttpRequest request) {
  return new ForwardToUnixSocket(request, socketPath);
}
```

The `request` is carried so the same relative-URI rewrite and header forwarding apply. The client's
existing `Host` header is forwarded unchanged (there is no meaningful host for a unix upstream; the
backend either ignores it or the application rewrote it via `request.withHeader` before returning the
action).

### 4. Dispatch in `HttpServerStage`

Add a branch to `applyRoutingDecision` alongside the existing `Forward` / `ForwardAndCapture` arms.
For `ForwardToUnixSocket` there is **no** `parseOrigin` call (the destination is explicit); it builds
a `UnixOriginDialer(fu.socketPath())` and a `ProxyRequestStage` with it:

```java
} else if (action instanceof RequestAction.ForwardToUnixSocket fu) {
  Executor exec = Objects.requireNonNull(this.executor, "executor");
  currentHandler =
      new ProxyRequestStage(
          parent, exec, serverListener, requestId,
          new UnixOriginDialer(fu.socketPath()),
          fu.request(), /* captureStream = */ null,
          this::installResponseGenerator);
  return startBodyOrDispatch(effective, currentHandler);
}
```

The existing `Forward` / `ForwardAndCapture` arms are updated only to build a `TcpOriginDialer` from
the parsed `Origin` (`origin.host()`, `origin.port()`, `origin.useTls()`, and the
`origin.useTls() ? originSocketFactory : SocketFactory.getDefault()` factory) instead of passing the
tuple — same values, new wrapper. Their `BAD_REQUEST`-on-null-`Origin` behaviour is unchanged.

### 5. README

Add a unix-socket bullet to the "Reverse proxy" section showing
`RequestAction.forwardToUnixSocket(Path.of("/run/backend.sock"), request)`.

## Security Considerations

- **Destination is trusted, never client-controlled (the load-bearing decision).** The socket path
  is a `java.nio.file.Path` supplied by application code in the `ConnectHandler`. It is **never**
  parsed from the request URI or `Host` header. This is deliberate: if a remote client could name the
  upstream socket path, a reverse proxy would become an arbitrary-local-socket connect primitive
  (SSRF to any AF_UNIX endpoint on the host — docker.sock, database admin sockets, etc.). By keeping
  the path out-of-band and typed as `Path`, the trust boundary is the same as the existing
  `forward(request)` API, where the app — not the client — decides whether/where to forward. An
  application that chooses to derive the path from request data owns that risk, exactly as it would
  for a TCP host.
- **No new NIO-thread blocking.** The dial and byte-pump run on the executor thread inside
  `OriginForwarder.run` (spawned via `executor.execute`), never on a selector thread — unchanged from
  the TCP path. A unix `connect()` is local and effectively immediate.
- **Read-timeout parity, not regression.** The unix `SocketChannel` (blocking, via `Channels`
  streams) has no read timeout — identical to the current TCP dial, which never sets `SO_TIMEOUT`. A
  hung backend ties up one executor thread in both cases; this change introduces no new exposure. Any
  future timeout hardening should cover both dialers uniformly and is out of scope here.
- **Resource leak on failed connect.** `UnixOriginDialer` uses the same `boolean ok` / `finally`
  close-on-failure guard as `FastCgiConnection.connectUnix`, so a channel is never leaked when
  `connect` throws; `OriginConnection` is `Closeable` and closed via `try (conn)` on the success
  path, matching the existing `socket.close()` cleanup.
- **Request smuggling / framing:** unchanged. The same `requestHeadersToBytes` (hop-by-hop stripping,
  relative-URI rewrite) and the same incremental response parser handle the unix path — the transport
  swap does not touch framing.

## Decisions

- **Decision:** Add one additive `RequestAction.ForwardToUnixSocket(request, socketPath)` variant +
  `forwardToUnixSocket(Path, request)` factory, rather than overloading `forward`. — *Rationale:* the
  destination shape genuinely differs (a `Path`, no host/port/TLS), the sealed interface makes an
  explicit variant the idiomatic extension point, and a distinct name makes the "not client-inferred"
  security property obvious at the call site.
- **Decision:** Encapsulate the dial behind an internal `OriginDialer` / `OriginConnection` seam;
  `OriginForwarder` becomes transport-agnostic. — *Rationale:* the `(host, port, useTls,
  SocketFactory)` tuple is the only obstacle and it recurs across `HttpServerStage` →
  `ProxyRequestStage` → `OriginForwarder`. One seam removes it in all three, the TCP impl is a
  behaviour-preserving extraction, and the unix impl slots in beside it without touching the
  byte-pump. It also makes a future `ForwardToUnixSocketAndCapture` and any timeout hardening
  one-place changes.
- **Decision:** Unix upstream is plaintext HTTP/1.1 only — no TLS/SNI. — *Rationale:* a unix socket
  is a local backend; TLS over AF_UNIX adds no meaningful confidentiality and no client asks for it.
  Keeping `useTls`/SNI exclusively in `TcpOriginDialer` keeps the unix path minimal.
- **Decision:** Forward the client's existing `Host` header unchanged to the unix backend. —
  *Rationale:* there is no host:port to synthesize; a local backend either ignores `Host` or the
  application rewrote it (via `request.withHeader`) before returning the action. Inventing a value
  would be surprising and lossy.
- **Decision:** Ship `forwardToUnixSocket` only; defer a capture-to-unix variant. — *Rationale:*
  keeps the API surface minimal for review; the dialer seam makes capture a small additive follow-up
  if a use case appears.

## Open Questions

None.

## Acceptance Criteria

- [x] `RequestAction.forwardToUnixSocket(Path, HttpRequest)` and the `ForwardToUnixSocket` record
      exist in `de.ofahrt.catfish.model.server.RequestAction`, with null-checks on both fields.
      (`RequestActionTest.forwardToUnixSocket_*`.)
- [x] A `ConnectHandler.applyLocal` returning `forwardToUnixSocket(path, request)` forwards a `GET`
      to a backend bound on a unix socket at `path` and returns the backend's `200` + body to the
      client — `UnixSocketIntegrationTest.reverseProxyToUnixSocketBackend` (TCP client → reverse
      proxy → Catfish `HttpEndpoint.onUnixSocket` backend), plus
      `OriginForwarderTest.unixSocket_forwards_returnsBackendResponse` against a raw
      `ServerSocketChannel` of family `UNIX`.
- [x] A request with a body is streamed to the unix backend and the backend's response body is
      returned — `OriginForwarderTest.unixSocket_streamsRequestBody` asserts the backend received the
      streamed `POST` body; `unixSocket_forwards_returnsBackendResponse` covers the response body.
      Body handling is the shared, transport-agnostic loop (unchanged from TCP).
- [x] A dial failure (no backend listening at `path`) yields a `502` to the client (the existing
      `sendErrorResponse` path), and no channel/fd is leaked (`UnixOriginDialer` uses the
      `ok`/`finally` close-on-failure guard) — `OriginForwarderTest.unixSocket_noBackend_returns502`.
- [x] Existing TCP forward / forward-and-capture tests remain green unchanged (the `TcpOriginDialer`
      extraction is behaviour-preserving), proving non-breakage.
- [x] The unix destination is never derived from the request URI or `Host` header: the path only
      flows in via `RequestAction.forwardToUnixSocket(Path, …)`; there is no `unix:`-scheme parsing
      in `HttpServerStage.parseOrigin` or anywhere else.
- [x] README "Reverse proxy" section documents `forwardToUnixSocket`.
- [x] Tests: the cases above are in `OriginForwarderTest` / `RequestActionTest` /
      `UnixSocketIntegrationTest` (all in their package suites); `bazel test //...` is green and
      `bazel run //:format.check` passes.

## Implementation Plan

- [x] PR 1: Introduce `OriginDialer` / `OriginConnection` and `OriginDialer.Tcp` as a
      behaviour-preserving extraction; refactor `ProxyRequestStage` and `OriginForwarder` to take an
      `OriginDialer`; update the two `HttpServerStage` forward arms to build `OriginDialer.tcp(...)`.
      No new public API, no behaviour change — existing proxy tests stayed green.
- [x] PR 2: Add `OriginDialer.Unix` (mirroring `FastCgiConnection.connectUnix`), the
      `RequestAction.ForwardToUnixSocket` variant + factory, and the `HttpServerStage` dispatch arm;
      add the unix-backend tests and the README note.

## Notes

- Risk is concentrated in PR 1's extraction (moving SNI/`startHandshake` and the close paths without
  changing behaviour); isolating it from the API addition keeps that risk reviewable against the
  unchanged existing tests.
- Follow-ups enabled by the dialer seam: `ForwardToUnixSocketAndCapture`, and a uniform upstream
  read/connect timeout across both dialers.
- Implementation note: the two dialer implementations landed as nested `OriginDialer.Tcp` /
  `OriginDialer.Unix` classes behind the `OriginDialer.tcp(...)` / `OriginDialer.unix(...)` static
  factories, rather than as separate top-level `TcpOriginDialer` / `UnixOriginDialer` files — same
  seam, less file sprawl.

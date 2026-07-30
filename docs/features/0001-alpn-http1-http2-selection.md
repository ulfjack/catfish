# 0001 — ALPN HTTP/1.1 + HTTP/2 protocol selection

- **Status:** Draft
- **Author(s):** agent (with Ulf Adams)
- **Created:** 2026-07-30
- **Related:** README "HTTP/2" section; `Http2Endpoint`, `HttpsEndpoint`, `SslServerStage`

## Problem

Today an application must decide, per TLS listener, whether it speaks HTTP/1.1 **or** HTTP/2:

- `HttpsEndpoint` advertises only `http/1.1` via ALPN and always installs an `HttpServerStage`.
- `Http2Endpoint` advertises only `h2` and always installs an `Http2ServerStage`.

There is no way to stand up a single HTTPS port that serves modern clients over HTTP/2 while
falling back to HTTP/1.1 for older clients — which is the standard expectation for an `https://`
endpoint. An operator who wants both must run two ports, and a client that doesn't speak the one
protocol a port offers simply fails to connect. The building blocks already exist (both stages
work, ALPN is already wired into `SslServerStage`); what's missing is choosing the inner stage
based on the ALPN result instead of ahead of time.

## Goals

- Offer both `h2` and `http/1.1` over ALPN on a single TLS listener and install the matching inner
  stage based on what the client negotiates.
- Preserve today's behaviour exactly for `HttpsEndpoint` (h1-only) and `Http2Endpoint` (h2-only).
- Correctly handle ALPN preference order and the no-ALPN / no-overlap fallback.

## Non-goals

- HTTP/2 over cleartext (`h2c`, prior-knowledge or Upgrade). This spec is ALPN-over-TLS only.
- Changing the `HttpHandler` SPI — the same handler already serves both protocols.
- HTTP/3 / QUIC.

## Approach

The one real constraint is in `SslServerStage`: it constructs its inner stage **eagerly in the
constructor** (via `InnerStageFactory`) and calls `next.connect(connection)` from its own
`connect()` — i.e. before the TLS handshake, so before the negotiated ALPN protocol is known. To
select h1 vs h2 we must **defer inner-stage creation until the handshake completes** and the
negotiated protocol is available from `SSLEngine.getApplicationProtocol()`.

Plan:

1. **Defer inner-stage creation in `SslServerStage`.** Change `InnerStageFactory` to receive the
   negotiated ALPN protocol string (`""` when none was negotiated), and call it from
   `transitionToOpen()` rather than the constructor. The eager `next.connect(...)` in
   `SslServerStage.connect()` moves to just after the inner stage is created, and
   `postHandshakeState` is computed there. The `InnerPipeline.replaceWith` path (used by
   CONNECT/MITM) is unaffected because it already runs post-handshake.
   - Care: `next` is currently non-null for the whole lifetime. It becomes null until handshake
     completion. Audit every use of `next` (`read`, `write`, `inputClosed`, `close`) for the
     pre-handshake window; in that window there is no inner stage yet, which matches the existing
     HANDSHAKE-state code paths that don't call into `next`.

2. **New endpoint type `HttpsEndpoint` gains protocol negotiation, or add a dedicated builder.**
   Preferred: give the two-protocol listener its own handler,
   `HttpProtocolNegotiatingHandler` (working name), analogous to `HttpServerHandler` /
   `Http2Handler`, that advertises `{"h2", "http/1.1"}` and, given the negotiated protocol, builds
   either an `Http2ServerStage` or an `HttpServerStage`. Expose it via a new
   `HttpsEndpoint`-shaped configurator — either a flag on `HttpsEndpoint`
   (`.withHttp2()`), or a new `HttpsAlpnEndpoint`. **Open question for review:** flag on the
   existing `HttpsEndpoint` vs. a new endpoint type. Leaning toward a flag to avoid duplicating the
   vhost/TLS/proxy configuration surface.

3. **ALPN selection semantics.** The JDK `SSLEngine` selects the protocol server-side from the
   advertised list against the client's list. We advertise in preference order `h2`, then
   `http/1.1`. On no overlap or a client that sends no ALPN, `getApplicationProtocol()` returns
   `""`; we fall back to **HTTP/1.1**, matching browser/`curl` behaviour for `https://`.

4. **Keep `Http2Endpoint` and h1-only `HttpsEndpoint` as thin configurations** over the same
   deferred-inner-stage machinery (single-element ALPN lists), so there's one code path.

## Public API impact

- **New:** a way to request h1+h2 on a TLS listener. Candidate A: `HttpsEndpoint.withHttp2()`
  returning `this`. Candidate B: `HttpsAlpnEndpoint.onAny(int)` mirroring `HttpsEndpoint`. Decide
  in review. Either way it is **additive**.
- **Unchanged:** `HttpHandler`, `Http2Endpoint`, existing `HttpsEndpoint` methods, all `model`
  types.
- **Internal (non-API):** `SslServerStage.InnerStageFactory` signature changes (package-private).
- **README:** document the combined-listener option under "HTTP/2".

## Security & correctness considerations

- **No NIO-thread blocking:** stage creation on handshake completion is synchronous and cheap; no
  new blocking is introduced.
- **Downgrade:** falling back to HTTP/1.1 on no-ALPN is intentional and standard; it is not a
  security downgrade (both are TLS-protected). We never fall back to cleartext.
- **h2 requirements:** RFC 9113 requires TLS 1.2+ and forbids certain ciphers for h2. The JDK
  enforces protocol/cipher selection; we advertise `h2` only over TLS, so this is unchanged from
  today's `Http2Endpoint`.
- **Null-safety:** deferring `next` creation introduces a nullable window; NullAway will force us
  to handle it explicitly, which is the desired outcome. Guard the pre-handshake window rather than
  suppressing.
- **SNI + ALPN interaction:** SNI selection already happens in `FIND_SNI` before the handshake;
  ALPN selection happens during the handshake. They are independent and both complete before
  `transitionToOpen()`.

## Acceptance criteria

1. A single TLS listener configured for h1+h2 serves an HTTP/2 client (ALPN `h2`) over
   `Http2ServerStage` and an HTTP/1.1 client (ALPN `http/1.1`) over `HttpServerStage`, using the
   same `HttpHandler`, and both receive a correct `200` response.
2. A client that offers only `http/1.1` against the combined listener is served over HTTP/1.1.
3. A client that offers only `h2` against the combined listener is served over HTTP/2.
4. A client that sends no ALPN extension against the combined listener is served over HTTP/1.1
   (documented fallback).
5. `HttpsEndpoint` (without opting into h2) still advertises only `http/1.1`, and `Http2Endpoint`
   still advertises only `h2` — verified by existing tests continuing to pass unchanged.
6. `getApplicationProtocol()` is observed to be `h2` / `http/1.1` respectively in a unit or
   integration test of the negotiating handler.
7. `bazel test //...` is green and `bazel run //:format.check` passes.

## Testing plan

- **Integration:** extend the HTTPS/HTTP2 integration tests to stand up a combined listener and
  connect twice — once forcing `http/1.1`, once forcing `h2` — asserting the negotiated protocol
  and response. The JDK client (`java.net.http.HttpClient`) can request a specific version; the
  existing `Http2IntegrationTest` shows the h2 client setup.
- **Unit:** a focused test on `SslServerStage` that the inner stage is created only after handshake
  completion and is chosen from the negotiated protocol (can use a fake `InnerStageFactory` that
  records the protocol string).
- **Regression:** existing `HttpsEndpointTest`, `Http2EndpointTest`, `SslServerStageTest` must pass
  unmodified (criterion 5).

## Rollout / compatibility notes

Purely additive. Existing endpoints behave identically. Applications opt into the combined listener
explicitly. No change required for current embedders.

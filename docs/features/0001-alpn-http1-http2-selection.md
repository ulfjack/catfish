---
id: 0001
title: ALPN HTTP/1.1 + HTTP/2 protocol selection
status: ready
owner: Ulf Adams
architecture_refs:
  - TLS / SslServerStage
  - Endpoints (HttpsEndpoint, Http2Endpoint)
---

# 0001 — ALPN HTTP/1.1 + HTTP/2 protocol selection

## Summary

Let a single TLS listener offer both `h2` and `http/1.1` over ALPN and install the matching inner
stage based on what the client negotiates, so one HTTPS port serves modern clients over HTTP/2 and
falls back to HTTP/1.1 for older ones.

## Goals

- Offer both `h2` and `http/1.1` over ALPN on a single TLS listener and install the matching inner
  stage based on what the client negotiates.
- Preserve today's behaviour exactly for `HttpsEndpoint` (h1-only) and `Http2Endpoint` (h2-only).
- Correctly handle ALPN preference order and the no-ALPN / no-overlap fallback.

## Non-Goals

- HTTP/2 over cleartext (`h2c`, prior-knowledge or Upgrade). This spec is ALPN-over-TLS only.
- Changing the `HttpHandler` SPI — the same handler already serves both protocols.
- HTTP/3 / QUIC.

## Background / Context

Today an application must decide, per TLS listener, whether it speaks HTTP/1.1 **or** HTTP/2:

- `HttpsEndpoint` advertises only `http/1.1` via ALPN and always installs an `HttpServerStage`.
- `Http2Endpoint` advertises only `h2` and always installs an `Http2ServerStage`.

There is no way to stand up a single HTTPS port that serves modern clients over HTTP/2 while
falling back to HTTP/1.1 for older clients — the standard expectation for an `https://` endpoint.
An operator who wants both must run two ports, and a client that doesn't speak the one protocol a
port offers simply fails to connect.

The building blocks already exist: both stages work, and ALPN is already wired into
`SslServerStage` (it sets `SSLParameters.setApplicationProtocols`). The one real constraint is that
`SslServerStage` constructs its inner stage **eagerly in the constructor** (via `InnerStageFactory`)
and calls `next.connect(connection)` from its own `connect()` — before the TLS handshake, so before
the negotiated ALPN protocol is known. (There is no `ARCHITECTURE.md` yet; the relevant code is
`SslServerStage`, `HttpServerHandler`, `Http2Handler`, `HttpsEndpoint`, `Http2Endpoint`.)

## Design

1. **Defer inner-stage creation in `SslServerStage`.** Change `InnerStageFactory` to receive the
   negotiated ALPN protocol string (`""` when none was negotiated), and call it from
   `transitionToOpen()` rather than the constructor. The eager `next.connect(...)` in
   `SslServerStage.connect()` moves to just after the inner stage is created, and
   `postHandshakeState` is computed there. The `InnerPipeline.replaceWith` path (used by
   CONNECT/MITM) is unaffected because it already runs post-handshake.
   - Care: `next` is currently non-null for the whole lifetime. It becomes null until handshake
     completion. Audit every use of `next` (`read`, `write`, `inputClosed`, `close`) for the
     pre-handshake window; in that window there is no inner stage yet, which matches the existing
     HANDSHAKE-state code paths that don't call into `next`. NullAway will force each site to be
     handled explicitly — guard, don't suppress.

2. **A protocol-negotiating handler.** Give the two-protocol listener its own handler,
   `HttpProtocolNegotiatingHandler` (working name), analogous to `HttpServerHandler` /
   `Http2Handler`, that advertises `{"h2", "http/1.1"}` and, given the negotiated protocol, builds
   either an `Http2ServerStage` or an `HttpServerStage`.

3. **ALPN selection semantics.** The JDK `SSLEngine` selects the protocol server-side from the
   advertised list against the client's list. We advertise in preference order `h2`, then
   `http/1.1`. On no overlap or a client that sends no ALPN, `getApplicationProtocol()` returns
   `""`; we fall back to HTTP/1.1.

4. **Keep `Http2Endpoint` and h1-only `HttpsEndpoint` as thin configurations** over the same
   deferred-inner-stage machinery (single-element ALPN lists), so there is one code path.

Public API: a new `HttpsEndpoint.withHttp2()` (returns `this`) opts a listener into h2+h1. This is
additive; `HttpHandler`, `Http2Endpoint`, existing `HttpsEndpoint` methods, and all `model` types
are unchanged. `SslServerStage.InnerStageFactory` is package-private and may change freely. README's
"HTTP/2" section documents the combined-listener option.

## Security Considerations

- **Downgrade:** falling back to HTTP/1.1 on no-ALPN / no-overlap is intentional and standard; both
  protocols are TLS-protected, so it is not a security downgrade, and we never fall back to
  cleartext.
- **No NIO-thread blocking:** inner-stage creation at handshake completion is synchronous and cheap;
  no new blocking is introduced on the selector thread.
- **Nullable `next` window:** deferring inner-stage creation makes `next` null until the handshake
  completes. NullAway forces every pre-handshake use to be handled explicitly (guard, not suppress),
  which prevents a use-before-init bug in the TLS state machine.
- **SNI + ALPN independence:** SNI selection happens in `FIND_SNI` before the handshake; ALPN
  selection happens during the handshake. Both complete before `transitionToOpen()`; neither can
  observe a partially-initialised stage.
- **h2 cipher/TLS requirements:** RFC 9113 requires TLS 1.2+ and forbids certain ciphers for `h2`;
  the JDK enforces this and we advertise `h2` only over TLS — unchanged from today's
  `Http2Endpoint`.

## Decisions

- **Decision:** Expose the combined listener as a flag on the existing `HttpsEndpoint`
  (`.withHttp2()`) rather than a new `HttpsAlpnEndpoint` type. — *Rationale:* avoids duplicating the
  vhost/TLS/proxy configuration surface that `HttpsEndpoint` already carries; the only difference is
  the advertised ALPN list and deferred stage selection.
- **Decision:** On no-ALPN or no-overlap, fall back to HTTP/1.1. — *Rationale:* matches
  browser/`curl` behaviour for `https://`; both protocols are TLS-protected so this is not a
  security downgrade, and we never fall back to cleartext.
- **Decision:** Advertise ALPN in preference order `h2`, then `http/1.1`. — *Rationale:* prefer the
  more capable protocol when the client supports it; the JDK `SSLEngine` picks the first server
  protocol the client also offers.

## Open Questions

None.

## Acceptance Criteria

- [ ] A single TLS listener configured for h1+h2 serves an HTTP/2 client (ALPN `h2`) over
      `Http2ServerStage` and an HTTP/1.1 client (ALPN `http/1.1`) over `HttpServerStage`, using the
      same `HttpHandler`, and both receive a correct `200` response.
- [ ] A client that offers only `http/1.1` against the combined listener is served over HTTP/1.1.
- [ ] A client that offers only `h2` against the combined listener is served over HTTP/2.
- [ ] A client that sends no ALPN extension against the combined listener is served over HTTP/1.1.
- [ ] `HttpsEndpoint` (without opting into h2) still advertises only `http/1.1`, and `Http2Endpoint`
      still advertises only `h2` — verified by existing tests continuing to pass unchanged.
- [ ] `getApplicationProtocol()` is observed to be `h2` / `http/1.1` respectively in a unit or
      integration test of the negotiating handler.
- [ ] Tests: integration test standing up a combined listener and connecting once forcing
      `http/1.1` and once forcing `h2`; unit test that `SslServerStage` creates the inner stage only
      after handshake completion and chooses it from the negotiated protocol.
- [ ] `bazel test //...` green and `bazel run //:format.check` passes.

## Implementation Plan

- [ ] PR 1: Defer inner-stage creation in `SslServerStage` — pass the negotiated ALPN protocol to
      `InnerStageFactory`, create the inner stage at `transitionToOpen()`, and handle the nullable
      pre-handshake `next` window. No behaviour change for existing single-protocol endpoints
      (single-element ALPN list); existing `SslServerStageTest`/endpoint tests stay green.
- [ ] PR 2: Add `HttpProtocolNegotiatingHandler` advertising `{"h2","http/1.1"}` and selecting the
      inner stage from the negotiated protocol; add `HttpsEndpoint.withHttp2()` to wire it. Unit
      test the selection.
- [ ] PR 3: Integration tests for the combined listener (force h1, force h2, no-ALPN fallback);
      README update under "HTTP/2".

## Notes

- Risk is concentrated in PR 1 (the nullable `next` window in the TLS state machine); keeping it a
  standalone, behaviour-preserving PR isolates that risk.
- SNI selection already happens in `FIND_SNI` before the handshake; ALPN selection happens during
  the handshake. They are independent and both complete before `transitionToOpen()`.
- RFC 9113 requires TLS 1.2+ and forbids certain ciphers for `h2`; the JDK enforces this, and we
  advertise `h2` only over TLS, unchanged from today's `Http2Endpoint`.

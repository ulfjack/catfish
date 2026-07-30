---
id: 0001
title: Unify TLS endpoints with ALPN HTTP/1.1 + HTTP/2 selection
status: ready
owner: Ulf Adams
architecture_refs:
  - TLS / SslServerStage
  - Endpoints (HttpsEndpoint, Http2Endpoint)
---

# 0001 — Unify TLS endpoints with ALPN HTTP/1.1 + HTTP/2 selection

## Summary

Make `HttpsEndpoint` advertise a configurable ALPN protocol set and install the matching inner stage
per connection, so a single HTTPS port can serve HTTP/2 and HTTP/1.1 by negotiation. The default
stays **`http/1.1` only** — identical to today's `HttpsEndpoint`, so existing users are unaffected —
and an application opts into h2 with `protocols(HTTP_2, HTTP_1_1)`. `Http2Endpoint` becomes a thin
**deprecated** shim over the unified endpoint (`protocols(HTTP_2)`), not removed — so this change is
entirely non-breaking.

## Goals

- One TLS endpoint (`HttpsEndpoint`) whose advertised ALPN protocols are configurable, defaulting to
  `http/1.1` only — byte-for-byte the current `HttpsEndpoint` behaviour, so the default is
  non-breaking.
- Let an application advertise `h2` + `http/1.1` (h2 preferred, h1 fallback) on one HTTPS port with
  `protocols(HTTP_2, HTTP_1_1)`.
- Install the matching inner stage (`Http2ServerStage` / `HttpServerStage`) based on the negotiated
  ALPN protocol, using the same `HttpHandler`.
- Preserve every capability the two old endpoints had, including "h2 only, reject h1" and "h1 only",
  as explicit configurations of the unified endpoint.
- Keep `Http2Endpoint` working as a `@Deprecated` shim that delegates to the unified endpoint with
  `protocols(HTTP_2)`, so existing code compiles and runs unchanged.

## Non-Goals

- HTTP/2 over cleartext (`h2c`, prior-knowledge or Upgrade). ALPN-over-TLS only.
- HTTP/3 / QUIC.
- Changing the `HttpHandler` SPI — one handler already serves both protocols.

## Background / Context

Today an application must decide, per TLS listener, whether it speaks HTTP/1.1 **or** HTTP/2, across
two public endpoint types:

- `HttpsEndpoint` advertises only `http/1.1` and always installs an `HttpServerStage`.
- `Http2Endpoint` advertises only `h2` and always installs an `Http2ServerStage`; a client offering
  only `http/1.1` finds no ALPN overlap and the connection fails.

There is no way to stand up a single HTTPS port that serves modern clients over HTTP/2 while falling
back to HTTP/1.1 for older ones — the standard expectation for an `https://` endpoint. This split
has always been a wart. Unifying it keeps `HttpsEndpoint`'s default behaviour intact (h1-only) and
adds an opt-in `protocols(...)` for h2; `Http2Endpoint` stays as a `@Deprecated` shim that delegates
to `protocols(HTTP_2)`, so nothing breaks and its users can migrate at leisure.

The building blocks exist: both stages work, and ALPN is already wired into `SslServerStage`
(`SSLParameters.setApplicationProtocols`). The one real constraint is that `SslServerStage`
constructs its inner stage **eagerly in the constructor** (via `InnerStageFactory`) and calls
`next.connect(connection)` from its own `connect()` — before the TLS handshake, so before the
negotiated ALPN protocol is known. Relevant code: `SslServerStage`, `HttpServerHandler`,
`Http2Handler`, `HttpsEndpoint`, `Http2Endpoint`, `CatfishHttpServer`. (No `ARCHITECTURE.md` yet.)

## Design

### 1. Defer inner-stage creation in `SslServerStage`

Change `InnerStageFactory` to receive the negotiated ALPN protocol string (`""` when none was
negotiated) and call it from `transitionToOpen()` rather than the constructor. The eager
`next.connect(...)` moves to just after the inner stage is created, where `postHandshakeState` is
computed. The `InnerPipeline.replaceWith` path (CONNECT/MITM) is unaffected — it already runs
post-handshake.

`next` currently is non-null for the whole lifetime; it becomes null until handshake completion.
Every use (`read`, `write`, `inputClosed`, `close`) must be audited for the pre-handshake window,
where there is no inner stage yet — this matches the existing HANDSHAKE-state paths that don't call
into `next`. NullAway forces each site to be handled explicitly (guard, don't suppress).

### 2. Unified endpoint with a configurable protocol set

Introduce a public enum for the offered application protocols and a configurator on `HttpsEndpoint`:

```java
public enum HttpProtocol { HTTP_2, HTTP_1_1 }

// on HttpsEndpoint, protocols listed in ALPN preference order:
public HttpsEndpoint protocols(HttpProtocol... protocols);
```

Default (no call) is `{HTTP_1_1}` — a single-entry ALPN list, byte-for-byte the current
`HttpsEndpoint`. A single internal handler (`HttpProtocolNegotiatingHandler`) advertises the
configured protocol ids, and given the negotiated protocol builds either an `Http2ServerStage` or an
`HttpServerStage`. When the set has one element the ALPN list has one entry, exactly reproducing the
old single-protocol endpoints.

Migration of the old capabilities (all still compile; the `Http2Endpoint` row is the recommended,
not required, migration):

| Old | New |
|---|---|
| `HttpsEndpoint...` (h1 only) | `HttpsEndpoint...` (default, unchanged) |
| `Http2Endpoint...` (h2 only, reject h1) | still works (deprecated) → `HttpsEndpoint...protocols(HTTP_2)` |
| (new) both, h2 preferred | `HttpsEndpoint...protocols(HTTP_2, HTTP_1_1)` |

### 3. ALPN selection semantics

The JDK `SSLEngine` selects the protocol server-side from the advertised list against the client's
list, honouring server preference order. On no overlap or a client that sends no ALPN,
`getApplicationProtocol()` returns `""`; the negotiating handler falls back to the **last**
(least-preferred) configured protocol — HTTP/1.1 in the default `{HTTP_1_1}` set and in a
`{HTTP_2, HTTP_1_1}` set. If the set is `{HTTP_2}` only, a no-ALPN / h1-only client has no overlap
and the connection is refused (reproducing the old `Http2Endpoint` strictness).

### 4. Deprecate `Http2Endpoint`

Keep `Http2Endpoint` and its `CatfishHttpServer.listen(Http2Endpoint)` overload, but mark both
`@Deprecated` (javadoc pointing at `HttpsEndpoint...protocols(HTTP_2)`). Reimplement `Http2Endpoint`
as a thin shim: it configures a unified endpoint / negotiating handler with `protocols(HTTP_2)`
rather than carrying its own ALPN + stage-construction logic, so there is one code path and the
deprecated type stays behaviourally identical (h2 only, reject h1). The old `Http2Handler` is folded
into the negotiating handler; `Http2Endpoint` delegates to it. Existing call sites (`BlobServer`,
`Http2IntegrationTest`, `Http2EndpointTest`) keep compiling untouched; the README gains a
deprecation + migration note.

## Security Considerations

- **Downgrade:** falling back to HTTP/1.1 when h2 isn't negotiated is intentional and standard; both
  protocols are TLS-protected, so it is not a security downgrade, and we never fall back to
  cleartext. An operator who requires h2 configures `protocols(HTTP_2)` and h1 clients are refused.
- **Nullable `next` window:** deferring inner-stage creation makes `next` null until the handshake
  completes; NullAway forces every pre-handshake use to be handled explicitly, preventing a
  use-before-init bug in the TLS state machine.
- **SNI + ALPN independence:** SNI selection happens in `FIND_SNI` before the handshake; ALPN during
  the handshake. Both complete before `transitionToOpen()`; neither observes a partially-initialised
  stage.
- **h2 cipher/TLS requirements:** RFC 9113 requires TLS 1.2+ and forbids certain ciphers for `h2`;
  the JDK enforces this and we advertise `h2` only over TLS — unchanged.
- **No NIO-thread blocking:** inner-stage creation at handshake completion is synchronous and cheap.

## Decisions

- **Decision:** Unify on `HttpsEndpoint` with a configurable protocol set; **deprecate** rather than
  remove `Http2Endpoint`. — *Rationale:* the two-endpoint split is the root wart; every capability
  survives as an explicit `protocols(...)` configuration, and a single endpoint is the correct
  long-term surface. Keeping `Http2Endpoint` as a `@Deprecated` shim over `protocols(HTTP_2)` makes
  the whole change non-breaking — existing code compiles and runs unchanged — while still steering
  new code to the unified endpoint and letting the shim be removed in a later major version. It also
  collapses to one code path (the negotiating handler), so the deprecated type carries no duplicate
  logic.
- **Decision:** Default protocol set is `{HTTP_1_1}` only — **not** `{HTTP_2, HTTP_1_1}`. —
  *Rationale:* this makes the default non-breaking: a default `HttpsEndpoint` behaves byte-for-byte
  as today (advertises only `http/1.1`), so existing `HttpsEndpoint` users are completely unaffected
  and no h2 is silently introduced. Opting into h2 is one explicit call,
  `protocols(HTTP_2, HTTP_1_1)`. A silent `{HTTP_2, HTTP_1_1}` default would change the on-the-wire
  protocol for every existing HTTPS deployment with an h2-capable client — an unnecessary and
  surprising behaviour change to avoid.
- **Decision:** No-ALPN / no-overlap falls back to the least-preferred configured protocol. —
  *Rationale:* matches browser/`curl` behaviour for the default set, and cleanly yields "refuse h1"
  when the set is `{HTTP_2}` only.
- **Decision:** Represent the offered protocols as a public `HttpProtocol` enum in ALPN preference
  order, rather than raw ALPN strings. — *Rationale:* type-safe, hides the `"h2"`/`"http/1.1"` id
  detail, and makes preference order explicit.

## Open Questions

None.

## Acceptance Criteria

- [ ] A default `HttpsEndpoint` (protocol set `{HTTP_1_1}`) advertises only `http/1.1` and serves an
      HTTP/1.1 client over `HttpServerStage` — byte-for-byte the current behaviour; an h2-capable
      client negotiates `http/1.1` (no silent h2).
- [ ] A `HttpsEndpoint...protocols(HTTP_2, HTTP_1_1)` endpoint serves an HTTP/2 client (ALPN `h2`)
      over `Http2ServerStage` and an HTTP/1.1 client (ALPN `http/1.1`) over `HttpServerStage`, using
      the same `HttpHandler`, both getting a correct `200`.
- [ ] A client offering only `http/1.1`, and a client sending no ALPN, against a
      `protocols(HTTP_2, HTTP_1_1)` endpoint are served over HTTP/1.1.
- [ ] `HttpsEndpoint...protocols(HTTP_2)` serves an h2 client and **refuses** an h1-only / no-ALPN
      client (reproducing old `Http2Endpoint` behaviour).
- [ ] `HttpsEndpoint...protocols(HTTP_1_1)` is equivalent to the default (advertises only
      `http/1.1`).
- [ ] `Http2Endpoint` and `CatfishHttpServer.listen(Http2Endpoint)` still exist, are marked
      `@Deprecated`, and behave identically (h2 only, reject h1) by delegating to the unified
      negotiating handler with `protocols(HTTP_2)`; existing call sites compile unchanged.
- [ ] `getApplicationProtocol()` is observed to be `h2` / `http/1.1` respectively in a unit or
      integration test of the negotiating handler.
- [ ] Tests: integration test on a `protocols(HTTP_2, HTTP_1_1)` endpoint forcing h1, forcing h2,
      and no-ALPN; a default-endpoint test asserting only `http/1.1` is advertised; a
      `protocols(HTTP_2)` test asserting h1 refusal; a unit test that `SslServerStage` creates the
      inner stage only after handshake completion and chooses it from the negotiated protocol.
- [ ] `bazel test //...` green and `bazel run //:format.check` passes.

## Implementation Plan

- [ ] PR 1: Defer inner-stage creation in `SslServerStage` — pass the negotiated ALPN protocol to
      `InnerStageFactory`, create the inner stage at `transitionToOpen()`, handle the nullable
      pre-handshake `next` window. Behaviour-preserving for existing single-protocol endpoints
      (single-element ALPN list); existing `SslServerStageTest`/endpoint tests stay green.
- [ ] PR 2: Add `HttpProtocol` enum + `HttpProtocolNegotiatingHandler` selecting the inner stage
      from the negotiated protocol; unit-test selection and the no-ALPN fallback.
- [ ] PR 3: Add `HttpsEndpoint.protocols(...)` (default `{HTTP_1_1}`, unchanged behaviour) wired to
      the negotiating handler.
- [ ] PR 4: Reimplement `Http2Endpoint` as a `@Deprecated` shim delegating to the negotiating
      handler with `protocols(HTTP_2)`; deprecate `listen(Http2Endpoint)`; fold `Http2Handler` into
      the negotiating handler. Existing call sites (`BlobServer`, `Http2IntegrationTest`,
      `Http2EndpointTest`) stay unchanged and green; add a README deprecation + migration note. A
      test asserts the shim still rejects h1-only clients.

## Notes

- Risk is concentrated in PR 1 (the nullable `next` window in the TLS state machine); keeping it a
  standalone, behaviour-preserving PR isolates that risk from the API additions.
- This change is **non-breaking**: the default `HttpsEndpoint` is unchanged and `Http2Endpoint`
  stays as a `@Deprecated` shim, so all existing code compiles and runs unchanged. No major version
  bump is required; a minor release with a deprecation note suffices. Actually removing
  `Http2Endpoint` is a separate future major-version cleanup.

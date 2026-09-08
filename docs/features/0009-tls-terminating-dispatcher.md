---
id: 0009
title: TLS-terminating dispatcher (reverse proxy) on HttpsEndpoint
status: ready
owner: Ulf Adams
architecture_refs:
  - Server (HttpsEndpoint)
  - Routing (VirtualHostRouter, ConnectHandler, ConnectDecision, RequestAction)
  - TLS (HttpsEndpoint.getSSLContext, sslInfos, SslServerStage, ssl/SSLInfo)
---

# 0009 — TLS-terminating dispatcher (reverse proxy) on HttpsEndpoint

## Summary

Let a `dispatcher` carry a TLS cert so it can **terminate TLS for a direct client and reverse-proxy**
to a backend — impossible today. Add one `HttpsEndpoint.dispatcher(SSLInfo, ConnectHandler)` overload
that registers the cert in the existing SNI map, plus a `RequestAction.forwardToTcp` variant so the
router can forward to a fixed TCP backend. Cert-bound `dispatcher` entries (and `addHost` entries)
coexist on one endpoint, selected by SNI. Uses the **existing TLS types (`SSLInfo`)**; no broader TLS
rework.

## Goals

- `HttpsEndpoint.onAny(443).dispatcher(sslInfo, router)` terminates TLS for a **direct** client (no
  `CONNECT`, no CA) and reverse-proxies to a backend.
- Multiple cert-bound `dispatcher` entries coexist on one endpoint, **SNI-selected**, and can coexist
  with `addHost` entries (serve some hosts, proxy others on one `:443`).
- A `RequestAction.forwardToTcp(host, port, request)` variant to forward to a fixed TCP backend
  (mirrors 0004's `forwardToUnixSocket`).
- Reuse existing TLS machinery: `SSLInfo`, the `sslInfos` SNI map / `getSSLContext`, `SslServerStage`.

## Non-Goals

- **Any broader TLS rework.** No `TlsCredentials`, no cert-derived hostname API / `--hostname`
  removal, no moving ALPN off the endpoint, no change to `addHost`'s signature or to `SSLInfo`. TLS
  stays as it is today except for the new dispatcher overload.
- **TLS cert reload / rotation** — separate, later.
- **Merging `addHost` and `dispatcher`.** They stay distinct (serve vs proxy).
- **Backend/upstream TLS, connection pooling, HTTP/2 to the backend** — unchanged (plaintext HTTP/1.1,
  per 0004).
- New *features* (path routing, static sites, ACME); those build on top (e.g. the shelved
  static-proxy-server proposal).

## Background / Context

Reverse proxying already exists: `HttpServerStage.applyRoutingDecision` → `ProxyRequestStage` →
`OriginForwarder`, dialed via the `OriginDialer` seam (`Tcp`/`Unix`, spec 0004). The routing decision
is a `RequestAction` returned by a `ConnectHandler` (the `dispatcher`).

Two gaps block a TLS-terminating reverse proxy:

1. **A dispatcher gets no server cert.** TLS certs attach only via `addHost` (populates `sslInfos`;
   `getSSLContext`, `HttpsEndpoint.java:147`). `dispatcher()` and `addHost()` are mutually exclusive
   (`VirtualHostRouter.java:25` throws), and in dispatcher mode `sslInfos` is empty, so a direct TLS
   client gets no cert (`getSSLContext` returns null). The only way a dispatcher gets a cert today is
   a `CONNECT` intercept (MITM) — no use for a plain reverse proxy.
2. **Fixed TCP backend.** TCP forwarding derives the origin from the request URI/`Host`
   (`HttpServerStage.parseOrigin`, forward-proxy semantics). A reverse proxy needs a fixed,
   operator-chosen backend. 0004 solved this for unix sockets with an explicit-destination
   `RequestAction.forwardToUnixSocket`; TCP needs the same.

## Design

### Before / after

**TLS-terminating reverse proxy** — *not expressible today.*

```java
// NEW
SSLInfo ssl = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, crtFile);
server.listen(
    HttpsEndpoint.onAny(443)
        .protocols(AlpnProtocol.HTTP_2, AlpnProtocol.HTTP_1_1)   // ALPN stays at endpoint level
        .dispatcher(ssl, router));                               // NEW: terminate TLS with ssl, then route
```

**Mixed serve + proxy on one `:443`** — *not expressible today (addHost XOR dispatcher).*

```java
// NEW
server.listen(
    HttpsEndpoint.onAny(443)
        .protocols(AlpnProtocol.HTTP_2, AlpnProtocol.HTTP_1_1)
        .addHost("www.example.com", new HttpVirtualHost(handler), siteSsl)   // served
        .dispatcher(apiSsl, apiRouter));                                     // reverse-proxied
```

### 1. `HttpsEndpoint.dispatcher(SSLInfo, ConnectHandler)`

New overload alongside the existing `dispatcher(ConnectHandler)`:

- Registers `sslInfo` in the endpoint's SNI map exactly as `addHost` does, so `getSSLContext` resolves
  it for a direct TLS client by SNI (via `SSLInfo.covers`). No hostname parameter — SNI selection
  already keys off the cert's covered names (existing `getSSLContext` behaviour), so this is *not* a
  new hostname mechanism, just the existing cert-by-SNI path.
- The paired `ConnectHandler` handles requests on connections terminated with that cert
  (`applyLocal`/`applyProxy` → `RequestAction`).
- ALPN comes from the endpoint's existing `.protocols(...)` (unchanged).

### 2. Coexistence + SNI selection

`HttpsEndpoint` holds a set of entries — `addHost` (cert + `HttpVirtualHost`) and cert-bound
`dispatcher` (cert + `ConnectHandler`). At handshake SNI selects the entry whose cert `covers()` the
name; that entry's cert terminates TLS and its handler/router serves the connection.

- The **`addHost`-xor-`dispatcher` guard is relaxed** to allow cert-bound entries of both kinds to
  coexist (`VirtualHostRouter.buildConnectHandler`). The *cert-less* `dispatcher(ConnectHandler)`
  (forward-proxy/MITM) remains standalone as today.
- **Overlap:** exact match beats wildcard; two entries covering the same name → throw at `listen()`.
- **Unknown SNI:** existing `unrecognized_name` alert. **SNI absent:** refuse — there is no default
  entry, and non-SNI traffic is not served (never guess a cert).

### 3. `RequestAction.forwardToTcp` (mirrors 0004)

Additive `RequestAction` variant + factory `forwardToTcp(String host, int port, HttpRequest request)`
and a `HttpServerStage` dispatch arm that builds `OriginDialer.tcp(host, port, false, ...)` — no
`parseOrigin` (destination is explicit). Byte-pump unchanged.

### 4. Out of scope, unchanged

`addHost(String, HttpVirtualHost, SSLInfo)`, `SSLInfo`, endpoint `.protocols()`, and the MITM
`ConnectDecision`/`mitmAll` path are all untouched.

## Security Considerations

- **Backend is operator-configured, never client-derived.** `forwardToTcp`'s host:port comes from the
  router (application/config), not the request URI/`Host` — no `parseOrigin`, no SSRF-to-arbitrary-origin
  primitive. Same trust boundary as 0004's unix path.
- **Cert covers the served name (`:authority` invariant).** A connection terminated with `sslInfo`
  serves only names the cert `covers()`; a request whose `Host`/`:authority` the cert does not cover is
  rejected (`421` h2 / `400`+close h1). This is the runtime backstop for a free-form dispatcher router
  whose targets aren't statically known, and it falls out of cert scoping + how clients coalesce.
- **Wrong-cert mismatch is fail-closed.** An under-covering cert makes the client reject the handshake
  (loud outage, no wrong content). Consider warning when two coexisting entries with different
  routers/handlers resolve to overlapping SANs (silent isolation loss is the only dangerous case).
- **No new NIO-thread blocking.** Cert/entry resolution runs where `getSSLContext` runs today; the
  dial/pump runs on the executor thread (unchanged from 0004).
- **MITM/ALPN/framing unchanged.** No change to the `ConnectDecision` intercept path, ALPN negotiation,
  or the request/response framing.

## Decisions

- **Decision:** Add `HttpsEndpoint.dispatcher(SSLInfo, ConnectHandler)` reusing the existing SNI cert
  map. — *Rationale:* smallest change that lets a dispatcher terminate TLS; no new TLS types.
- **Decision:** Cert-bound `dispatcher` and `addHost` entries **coexist**, SNI-selected; relax the
  `xor` guard for cert-bound entries. — *Rationale:* multi-host reverse proxy already needs several
  cert-bound dispatchers on one endpoint, and admitting `addHost` alongside is then free (mixed
  serve/proxy on one `:443`). The cert-less `dispatcher` stays standalone.
- **Decision:** Add `RequestAction.forwardToTcp` (mirrors 0004's `forwardToUnixSocket`). — *Rationale:*
  a reverse proxy needs a fixed, operator-chosen backend; deriving it from the request would be wrong
  and an SSRF hole.
- **Decision:** Use the existing `SSLInfo` type; no `TlsCredentials`, no `addHost`/`SSLInfo`/ALPN
  changes. — *Rationale:* explicitly scoped out; keeps this change minimal and non-breaking for
  serving users.
- **Decision:** The `:authority` coverage rule is a correctness invariant, not a policy. — *Rationale:*
  falls out of cert scoping + client coalescing.
- **Decision:** Argument order is **cert-first**: `dispatcher(SSLInfo, ConnectHandler)`. — *Rationale:*
  TLS-before-routing; reads as "this cert, routed by this handler."
- **Decision:** Relaxing the `xor` guard applies **only to cert-bound entries**; the guard is **kept**
  for the cert-less `dispatcher(ConnectHandler)` + `addHost` combination. — *Rationale:* cert-bound
  entries all register certs and disambiguate by SNI, so they compose; a cert-less dispatcher has no
  cert to place in the SNI map, so mixing it with `addHost` stays ambiguous and remains an error.
- **Decision:** **No default entry; all non-SNI traffic is refused**, even with a single entry. —
  *Rationale:* never guess a cert; a hard, uniform rule is simpler and safer than a single-entry
  special case.

## Open Questions

None.

## Acceptance Criteria

- [ ] `HttpsEndpoint.onAny(443).dispatcher(sslInfo, router)` terminates TLS for a **direct** client (no
      `CONNECT`, no CA) and reverse-proxies a `GET`/`POST` to a backend, returning its response.
- [ ] A cert-bound `dispatcher` entry and an `addHost` entry **coexist** on one endpoint and are
      correctly SNI-selected.
- [ ] `RequestAction.forwardToTcp(host, port, request)` exists (port/null validation) and forwards to a
      fixed TCP backend without `parseOrigin`; a request for an uncovered `:authority` gets `421`/`400`.
- [ ] Two entries covering the same name throw at `listen()`; non-SNI traffic is refused (no default).
- [ ] Existing serving / MITM / unix-reverse-proxy tests remain green (the cert-less `dispatcher` and
      `addHost` paths are unchanged).
- [ ] Tests join their suites; `bazel test //...` green; `format.check` passes; NullAway clean.

## Implementation Plan

- [ ] PR 1: `RequestAction.forwardToTcp` variant + `HttpServerStage` dispatch arm + tests (additive,
      mirrors 0004 PR 2).
- [ ] PR 2: `HttpsEndpoint.dispatcher(SSLInfo, ConnectHandler)` registering the cert in the SNI map;
      relax the `xor` guard for cert-bound entries; SNI selection over entries; end-to-end
      TLS-terminating reverse proxy + mixed serve/proxy tests.
- [ ] PR 3: SNI edges (overlap error, SNI-absent, exact-over-wildcard) + `:authority`/`421`
      enforcement + tests.

## Notes

- **The shelved [static-proxy-server proposal](../proposals/static-proxy-server.md) builds on this**
  (a proxied host is a cert-bound `dispatcher(sslInfo, reverseProxyRouter)` entry). It is on ice; only
  0009 is active.
- The `TlsCredentials` / cert-derived-hostnames / ALPN-per-host ideas explored earlier are **out of
  scope** here; if wanted, they are a separate, later proposal.

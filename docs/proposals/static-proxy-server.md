---
id: PROPOSAL
title: Standalone TLS-terminating static + reverse-proxy server ("catfish-serve")
status: proposal
owner: Ulf Adams
architecture_refs:
  - Server (CatfishHttpServer, HttpEndpoint, HttpsEndpoint)
  - Routing (HttpVirtualHost, VirtualHostRouter, ConnectHandler, RequestAction)
  - Proxy (ProxyRequestStage, OriginForwarder, OriginDialer)
  - Static (servlets/DirectoryServlet)
  - TLS (ssl/SSLContextFactory, SNIParser)
---

# Standalone TLS-terminating static + reverse-proxy server ("catfish-serve")

> **On ice (2026-09-08).** Shelved pending (a) [spec 0009](../features/0009-tls-terminating-dispatcher.md),
> which it builds on, and (b) a decision on the config-file format. Only 0009 is active. Kept as a
> proposal because the config format is still an open question.

## Summary

Ship a standalone, config-file-driven server binary built on the existing Catfish library that can
replace nginx/Apache for the common single-machine case: **terminate TLS, serve certbot's
`.well-known/acme-challenge/` over plain HTTP, and reverse-proxy everything else to a local backend**
— routed per hostname (SNI). Configuration is a small line-oriented text file; certs come from
certbot's live directory and hot-reload on `catfish -s reload`.

## Goals

- A runnable binary (`catfish`) that reads a text config file (`-c <file>`) and serves traffic with
  no application code required.
- **Port 80:** serve `/.well-known/acme-challenge/*` from a shared webroot; 301-redirect all other
  requests to `https://` (same host + path).
- **Port 443:** terminate TLS (SNI per host, ALPN offering `h2` + `http/1.1`) and reverse-proxy
  **all** requests to that host's backend over HTTP/1.1.
- Per-host config is minimal: a hostname → backend mapping, with an optional per-host cert override.
  Everything else is global with sane defaults.
- Backends may be `host:port` (TCP) or `unix:/path` (unix domain socket, reusing 0004).
- WebSocket / `Upgrade` requests pass through to the backend.
- Proxied requests carry `Host`, `X-Forwarded-For`, and `X-Forwarded-Proto`.
- Certs load from `cert-dir/<host>/{fullchain,privkey}.pem`; `catfish -s reload` re-reads config and
  certs with zero dropped in-flight connections (wired to certbot `--deploy-hook`).
- `catfish -t` validates a config file and exits non-zero on error without binding ports.

## Non-Goals

- **Embedded ACME / automatic HTTPS (Caddy-style).** v1 depends on certbot for issuance and renewal.
  Deliberately deferred — see Notes; the `.well-known` serving here is the foundation it will reuse.
- **General static-site serving** beyond the `.well-known/acme-challenge/` carve-out. No `root` for
  arbitrary paths, no directory listings, no `index`, no `try_files`.
- **Path-based routing.** One backend per host; the only path special-case is the ACME challenge
  prefix on :80. (Multiple backends / `location`-style routing is a later spec.)
- **Per-host overrides of global knobs** (protocols, WebSocket, forwarded headers). Global-only in v1.
- **TLS to the backend, upstream connection pooling/keep-alive, HTTP/2 to the backend.** The upstream
  request is one plaintext HTTP/1.1 connection per proxied request — unchanged from 0004.
- **Access/error log configuration, gzip toggles, rate limiting, basic auth.** Later specs.
- **Native-image / jlink packaging.** v1 ships a deploy-jar + launcher script; a self-contained image
  is a follow-up.

## Background / Context

Catfish is today a *library*: configuration is entirely programmatic via `HttpEndpoint` /
`HttpsEndpoint` / `HttpVirtualHost` builders, and the only runnable entry points are the `example/*Main`
demos with trivial `args` parsing. There is **no** config-file parser and **no** production CLI. This
spec adds that shell; almost every capability it needs already exists:

- **TLS + SNI + ALPN:** `ssl/SSLContextFactory.loadPemKeyAndCrtFiles`, `SslServerStage`, `SNIParser`,
  `HttpsEndpoint.protocols(AlpnProtocol...)` (spec 0001).
- **Virtual hosts:** `HttpVirtualHost` / `VirtualHostRouter` — one host carries its own TLS context
  and handler. This is the natural home for "keyed by hostname".
- **Static files:** `servlets/DirectoryServlet` + MIME registry in `utils/`.
- **Reverse proxy:** the forward path `HttpServerStage.applyRoutingDecision` → `ProxyRequestStage` →
  `OriginForwarder`, dialed through the `OriginDialer` seam with `Tcp` / `Unix` implementations
  (spec 0004). The routing decision is a `RequestAction` returned from a `ConnectHandler`.

The **first gap**: TCP forwarding today derives the backend `Origin` from the request URI / `Host`
header (`HttpServerStage.parseOrigin`, forward-proxy semantics). A reverse proxy needs a **fixed**
backend independent of what the client sent. Spec 0004 already solved the analogous problem for unix
sockets by adding an explicit-destination `RequestAction.forwardToUnixSocket(Path, request)` that
bypasses `parseOrigin`. We mirror that for TCP.

The **second gap (discovered during design):** per-host TLS and reverse-proxy routing cannot be
combined the obvious way. TLS certs attach *only* via `HttpsEndpoint.addHost(host, vhost, sslInfo)`,
which populates the SNI→cert map (`HttpsEndpoint.getSSLContext`, `HttpsEndpoint.java:147`). A custom
`ConnectHandler` is set via `dispatcher(...)`, and `dispatcher()` and `addHost()` are **mutually
exclusive** — `VirtualHostRouter.buildConnectHandler` throws `IllegalStateException` if both are set
(`VirtualHostRouter.java:25`), and in dispatcher mode the SNI map is empty so there is no server cert.
So we cannot "set a per-host cert via `addHost` *and* a routing dispatcher that returns
`forwardToTcp`." But `addHost` is itself just a built-in dispatcher: when no custom dispatcher is set,
`buildConnectHandler` synthesizes a `ConnectHandler` that routes `applyLocal`/`applyProxy` by `Host`
through the vhost map (`VirtualHostRouter.java:33-63`). The fix is therefore to teach that built-in
synthesis to emit a proxy `RequestAction` for a host that is configured as a reverse-proxy target —
keeping TLS-via-`addHost` intact. `HttpVirtualHost` currently carries only an `HttpHandler`
(`HttpVirtualHost.java:9`); we add a reverse-proxy backend as an alternative to the handler.

## Design

### 1. New public API: explicit-destination TCP forward (mirrors 0004)

In `de.ofahrt.catfish.model.server.RequestAction` (sealed), add — purely additive:

```java
/** Forward the request to a fixed TCP backend, independent of the request URI / Host header. */
record ForwardToTcp(HttpRequest request, String host, int port) implements RequestAction {
  public ForwardToTcp {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(host, "host");
    if (port < 1 || port > 65535) throw new IllegalArgumentException("port");
  }
}

static RequestAction forwardToTcp(String host, int port, HttpRequest request) {
  return new ForwardToTcp(request, host, port);
}
```

`HttpServerStage.applyRoutingDecision` gains a `ForwardToTcp` arm alongside the existing
`Forward` / `ForwardAndCapture` / `ForwardToUnixSocket` arms. Like the unix arm, it does **not** call
`parseOrigin`; it builds `OriginDialer.tcp(host, port, /*useTls=*/false, SocketFactory.getDefault())`
and a `ProxyRequestStage`. No other proxy code changes — the byte-pump is transport- and
destination-agnostic.

### 2. Per-host TLS + reverse-proxy routing → depends on the API rebuild

**Superseded / provisional.** Combining per-host TLS with reverse-proxy routing does not work on the
current API (`dispatcher()` xor `addHost()`; TLS reachable only via `addHost`; a dispatcher gets a
cert only through MITM `CONNECT`). This is addressed by
[spec 0009](../features/0009-tls-terminating-dispatcher.md): on that API a proxied host is a cert-bound
`dispatcher(sslInfo, reverseProxyRouter)` entry (using the existing `SSLInfo` type), coexisting with
`addHost` entries on one `:443` and SNI-selected. **The §4 wiring below builds on 0009**; the earlier
"reverse-proxy `HttpVirtualHost` variant" sketch here has been dropped as a bolt-on.

### 3. Config model and parser

New package `de.ofahrt.catfish.config`. A plain data model:

```java
record ServerConfig(GlobalConfig global, List<HostConfig> hosts) {}
record GlobalConfig(int httpPort, int httpsPort, Path acmeWebroot, Path certDir) {}
record HostConfig(String hostname, Backend backend, @Nullable Path certOverride) {}
sealed interface Backend { record Tcp(String host, int port) ...; record Unix(Path path) ...; }
```

**Config file format** — line-oriented, one `keyword args...` directive per line; `#` comments;
blank lines ignored; no block/brace nesting, no whitespace significance:

```
# global (all optional; defaults shown)
listen-http    80
listen-https   443
acme-webroot   /var/lib/catfish/acme
cert-dir       /etc/letsencrypt/live      # expects <cert-dir>/<host>/{fullchain,privkey}.pem

# one line per virtual host:  host <name> backend <target> [cert <dir>]
host  example.com       backend  127.0.0.1:8080
host  api.example.com   backend  unix:/run/api.sock
host  blog.example.com  backend  127.0.0.1:2368   cert /etc/ssl/blog
```

`backend` target grammar: `HOST:PORT` (TCP) or `unix:PATH` (unix socket). Parser is a hand-written
recursive-line reader (no dependency); it collects **all** errors with line numbers rather than
failing on the first, and reports them together (better `-t` UX).

### 4. Config → server wiring

A `ServerBuilder` translates a `ServerConfig` into a running `CatfishHttpServer` using the cert-bound
`dispatcher` from spec 0009 (so a direct TLS client is served — see §2):

- **HTTPS endpoint** on `httpsPort`, built with `.protocols(h2, http/1.1)`. For each `HostConfig`, a
  cert-bound `dispatcher(sslInfo, router)` entry (per [spec 0009](../features/0009-tls-terminating-dispatcher.md)):
  - `sslInfo` from `SSLContextFactory.loadPemKeyAndCrtFiles(certPath, keyPath)`, with paths from
    `certOverride` or `certDir/<hostname>/{fullchain,privkey}.pem`; registered in the SNI map, so it
    works for direct TLS clients (no `CONNECT`). SNI selects by `SSLInfo.covers`; unknown SNI →
    existing `unrecognized_name`. (The `certbot on` SAN-indexed store discussed separately is not yet
    folded into this draft.)
  - `router` reverse-proxies to the host's `Backend` (`Tcp` / `Unix`) via `forwardToTcp` /
    `forwardToUnixSocket` with forwarded headers applied.

  (Until 0009 lands, this cannot be expressed on the current API — see §2.)
- **HTTP endpoint** on `httpPort`: a single handler that serves `GET /.well-known/acme-challenge/*`
  from `acmeWebroot` via `DirectoryServlet` (scoped to that subtree) and 301-redirects everything else
  to `https://<Host><path>` (reusing `servlets/RedirectServlet` semantics).

**Forwarded headers** (set in the `ConnectHandler` before returning the action, via
`request.withHeader`): `X-Forwarded-For` (append client IP), `X-Forwarded-Proto: https`, and `Host`
preserved as received. Hop-by-hop stripping is already handled by `OriginForwarder`.

**WebSocket / Upgrade:** an `Upgrade: websocket` request is forwarded like any other; the existing
forward path streams the switched-protocol bytes bidirectionally (verified in acceptance criteria).

### 5. CLI and process lifecycle

New `java_binary` `//java/de/ofahrt/catfish/serve:catfish` with `Main`:

- `catfish -c <file>` — load, bind, serve (foreground; systemd `Type=simple`).
- `catfish -t [-c <file>]` — parse + validate (incl. cert files exist/readable), print result, exit
  0/1; never binds ports.
- `catfish -s reload` / `-s stop` — signal a running instance. **Mechanism:** the running process
  listens on a unix control socket (path from a fixed runtime dir, e.g. `/run/catfish.sock`); the
  `-s` invocation connects and sends the verb. `reload` re-parses the config and rebuilds TLS
  contexts / vhost routing atomically (swap the `VirtualHostRouter`), leaving established connections
  and listening sockets untouched — zero dropped in-flight requests. A reload whose new config fails
  to parse or is missing a cert is rejected and the old config stays live.

### 6. Packaging & ops

- Bazel `_deploy.jar` + a `/usr/bin/catfish` launcher wrapper (`exec java -jar …`).
- A sample `catfish.conf`, a `systemd` unit (`ExecStart=/usr/bin/catfish -c /etc/catfish/catfish.conf`,
  `ExecReload=/usr/bin/catfish -s reload`), and a certbot `--deploy-hook` snippet
  (`catfish -s reload`) shipped under `docs/` / `examples/`.
- README section documenting the binary, the config format, and the certbot workflow.

## Security Considerations

- **Backend is operator-configured, never client-controlled.** The TCP host:port and unix path come
  only from the config file, exactly like 0004's `forwardToUnixSocket`. `parseOrigin` (which reads the
  request URI / `Host`) is **not** used on these paths, so a client cannot redirect the proxy to an
  arbitrary origin (no SSRF / arbitrary-socket-connect primitive). This is the load-bearing property.
- **ACME webroot path traversal.** The `.well-known/acme-challenge/` handler must serve *only* files
  under `acmeWebroot` and reject `..`, absolute-path, and symlink-escape attempts. `DirectoryServlet`
  already canonicalizes and confines to its root; the handler scopes it to the challenge subtree and
  serves `GET`/`HEAD` only. Challenge tokens are public by design, so no auth concern — but nothing
  outside that subtree may be reachable on :80.
- **No NIO-thread blocking.** File reads for challenges and all upstream dial/pump work run on the
  executor thread (proxy path unchanged from 0004; static reads go through the existing servlet path,
  not a selector thread). Config reload swaps an immutable router reference — no lock held on a
  selector thread.
- **Cert/key handling.** Keys are read from disk (typically `/etc/letsencrypt/live/<host>/privkey.pem`,
  root-only). `catfish -t` verifies readability without exposing contents; parse errors must not echo
  key bytes. The control socket (`-s`) is a unix socket with restrictive permissions — reload/stop are
  privileged operations and must not be exposed over TCP.
- **TLS/ALPN:** unchanged from specs 0001/0005. Unknown SNI → `unrecognized_name`. Client protocol
  (h2/http1.1) is independent of the upstream, which is always HTTP/1.1.
- **Request smuggling / framing:** unchanged. The same `requestHeadersToBytes` (hop-by-hop stripping,
  relative-URI rewrite) and incremental response parser handle every forward path.
- **Redirect on :80:** the 301 `Location` is built from the request `Host` + path; the `Host` is
  reflected, so it must be validated (no CRLF / control chars) before being placed in the header — the
  existing header model rejects invalid header values, which covers this.

## Decisions

- **Decision:** Line-oriented directive config (`keyword args`), not nginx-style blocks, not TOML. —
  *Rationale:* the config is genuinely flat (a global section + a list of host→backend lines); blocks
  and nested serialization formats add ceremony for no structure. One directive per line is trivial to
  parse, diff, and hand-edit, has no whitespace significance, and reads cleanly. (User explicitly
  rejected TOML and nginx syntax.)
- **Decision:** Keyed by hostname; per-host config is backend + optional cert override, everything
  else global. — *Rationale:* matches SNI/`HttpVirtualHost` internally and certbot's per-host live
  dir; in this use case nothing else actually varies per host, so global defaults keep a host entry to
  one line.
- **Decision:** Cert paths auto-derive from `cert-dir/<hostname>/` unless overridden. — *Rationale:*
  matches certbot's `/etc/letsencrypt/live/<domain>/` layout, so the common case needs no cert lines.
- **Decision:** `.well-known/acme-challenge/` served on **:80 (plain HTTP)**, not :443. — *Rationale:*
  certbot `http-01` validation is fetched over HTTP on port 80; serving it on 443 would not satisfy
  webroot issuance.
- **Decision:** Add `RequestAction.forwardToTcp(host, port, request)` mirroring 0004's unix variant,
  rather than reusing URI/`Host`-derived `forward`. — *Rationale:* a reverse proxy needs a fixed,
  operator-chosen backend; deriving it from the request would be both wrong (client controls it) and a
  security hole. The sealed-interface + explicit-variant pattern is already established by 0004.
- **Decision:** Reload/stop via a unix control socket, config swap is atomic. — *Rationale:* enables
  certbot `--deploy-hook` zero-downtime cert reload without dropping connections; unix socket keeps the
  privileged control channel off the network. (POSIX signal handling from the JVM is fragile; a
  control socket is portable and testable.)
- **Decision:** v1 depends on certbot (path A); embedded ACME (path B) is a follow-up. —
  *Rationale:* honors the original "integrates with certbot" requirement, ships soonest, and A's
  `.well-known`-on-:80 machinery is exactly what an embedded `http-01` client reuses, so B is additive.
- **Decision:** Deploy-jar + launcher for v1, not native-image/jlink. — *Rationale:* smallest path to
  a runnable binary on a host with a JRE; a self-contained image is an orthogonal packaging follow-up.
- **Decision:** One backend per host, only special-casing the ACME prefix. — *Rationale:* covers the
  stated use case ("everything else goes to the backend"); path routing is a separable later feature.

## Open Questions

- [ ] **Config-file format.** TOML and nginx-style blocks were rejected; a line-oriented format is
      sketched in §3 but unconfirmed. Blocks the config parser.
- [ ] **`certbot on` / SAN-indexed cert store.** Discussed (derive/verify certs by cert SANs rather
      than by hostname-directory) but not yet folded into this draft.
- [ ] **Depends on [spec 0009](../features/0009-tls-terminating-dispatcher.md)** landing (the
      cert-bound TLS-terminating `dispatcher`).

## Acceptance Criteria

- [ ] `RequestAction.forwardToTcp(String, int, HttpRequest)` + `ForwardToTcp` record exist with
      null/port validation; `HttpServerStage` has a `ForwardToTcp` arm that bypasses `parseOrigin`.
      (`RequestActionTest`, `HttpServerStage` dispatch test.)
- [ ] The config parser accepts the documented directives, rejects unknown directives / malformed
      backends / duplicate hostnames, and reports **all** errors with line numbers.
      (`ConfigParserTest` incl. golden good/bad files.)
- [ ] `ServerBuilder` builds a running server from a `ServerConfig`: an end-to-end integration test
      starts a stub backend, drives an HTTPS request through the binary, and asserts the backend
      response is returned with `X-Forwarded-For` / `X-Forwarded-Proto: https` / `Host` set.
- [ ] TCP backend and unix-socket backend both work end-to-end (the unix path reuses 0004).
- [ ] `GET /.well-known/acme-challenge/<token>` on :80 returns the file from `acme-webroot`; a path
      traversal (`../`) is rejected; any other :80 path returns 301 to `https://` with the same host +
      path. (`AcmeChallengeIntegrationTest`.)
- [ ] A WebSocket upgrade request is forwarded and bytes flow bidirectionally through the proxy.
- [ ] `catfish -t` exits non-zero on a bad config (incl. missing/unreadable cert) without binding
      ports, and exits 0 on a good one.
- [ ] `catfish -s reload` swaps to a new config with zero dropped in-flight connections; a reload with
      an invalid new config is rejected and the old config stays live. (`ReloadIntegrationTest`.)
- [ ] Sample `catfish.conf`, systemd unit, and certbot deploy-hook snippet exist; README documents the
      binary + config + certbot workflow.
- [ ] Tests: all of the above join their package suites; `bazel test //...` green; `bazel run
      //:format.check` passes; NullAway clean.

## Implementation Plan

- [ ] PR 1: `RequestAction.forwardToTcp` variant + `HttpServerStage` dispatch arm + tests. (Additive,
      no behaviour change to existing paths — mirrors 0004 PR 2.)
- [ ] PR 2: `config` package — model records + line-oriented parser with multi-error reporting +
      `ConfigParserTest` and golden files. No server wiring yet.
- [ ] PR 3: `ServerBuilder` (config → `CatfishHttpServer`): HTTPS vhosts with per-host cert loading +
      forwarding `ConnectHandler`; :80 ACME + redirect handler; end-to-end integration tests (TCP +
      unix backends, ACME challenge, redirect, forwarded headers, WebSocket).
- [ ] PR 4: `serve/Main` CLI (`-c`, `-t`), `java_binary` deploy-jar target, launcher script, sample
      config, systemd unit, README section.
- [ ] PR 5: control socket + `-s reload`/`-s stop`, atomic config/cert swap, `ReloadIntegrationTest`,
      certbot deploy-hook snippet.

## Notes

- **Follow-up — embedded ACME (path B / Caddy-style automatic HTTPS).** Add an `http-01` ACME client
  (e.g. `acme4j`): obtain/renew certs itself, write challenge responses into the same webroot this
  spec already serves on :80, store certs, and trigger the same atomic reload on renewal. A host entry
  would then need only the hostname — no certbot, no cert-dir. This spec's :80 serving and reload
  machinery are the foundation; B is intentionally additive.
- **Follow-up — packaging:** jlink/native-image self-contained binary.
- **Follow-up — richer routing:** path-based `location`-style rules / multiple backends per host, and
  general static-site serving, once a concrete need appears.
- Risk is concentrated in PR 3 (wiring) and PR 5 (atomic reload); PRs 1–2 are low-risk additive
  building blocks that can land and be reviewed independently.

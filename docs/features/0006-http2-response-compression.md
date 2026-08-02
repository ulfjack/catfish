---
id: 0006
title: HTTP/2 response compression parity via a shared response-writer decorator
status: ready
owner: Ulf Adams
architecture_refs:
  - HTTP/1.1 response generation (LocalHttpRequestStage.ResponseWriterImpl)
  - HTTP/2 response generation (Http2ServerStage.Http2ResponseWriter)
  - Response compression (CompressionPolicy)
---

# 0006 — HTTP/2 response compression parity via a shared response-writer decorator

## Summary

HTTP/2 responses are never compressed: only the HTTP/1.1 writer applies `CompressionPolicy`, so an
h2 client that sent `accept-encoding: gzip` still gets an uncompressed body. Fix the bug by extracting
response compression into a single `HttpResponseWriter` decorator that wraps both protocols'
writers, split content-type *worthiness* (policy) from accept-encoding *negotiation* (decorator) so a
second coding is a drop-in, and add `application/json` to the default compressible set.

## Goals

- HTTP/2 responses are gzip-compressed under exactly the same conditions as HTTP/1.1 today.
- Response compression has **one** implementation shared by both protocols; the per-protocol writers
  become compression-agnostic (the HTTP/1.1 writer *loses* its gzip code rather than the HTTP/2 writer
  gaining a copy).
- Content-coding selection is negotiated against the client's `accept-encoding` in one place, so
  adding a coding later is a localized change, not a cross-protocol edit.
- `application/json` (and `; charset=…` variants) is compressed by the built-in `CompressionPolicy`.

## Non-Goals

- `deflate`, `br` (brotli), `zstd` response codings. gzip is what this fixes; `deflate` is a trivial
  follow-up under the design here, and `br`/`zstd` require a third-party library and belong in a
  later spec (mirroring spec 0002's request-side non-goals).
- Response-size-based gating ("don't compress tiny bodies"). A real concern, but a separate axis from
  this refactor; a possible follow-up.
- Changing the default: `HttpVirtualHost` still defaults to `CompressionPolicy.NONE`. Compression
  stays opt-in.
- Request-body decoding (spec 0002) and any proxy/forward behaviour (bodies pass through raw).

## Background / Context

`CompressionPolicy.shouldCompress(HttpRequest, String mimeType)` is consulted only by
`LocalHttpRequestStage.ResponseWriterImpl` (HTTP/1.1). It gzips the body, sets `Content-Encoding:
gzip` + `Vary: Accept-Encoding`, and recomputes `Content-Length` (buffered) or wraps the streamed
`OutputStream` in `GZIPOutputStream`. `Http2ServerStage.Http2ResponseWriter` has none of this — it
encodes the response body verbatim — so h2 never compresses even though `RequestAction.ServeLocally`
carries the same `compressionPolicy()`.

The built-in `CompressionPolicy.COMPRESS` currently does two things in one method: a **content-type
whitelist** check and an **accept-encoding** check (`HttpAcceptEncoding.parse(...).recommend(List.of("gzip"))`).
The negotiation primitive already returns *which* coding the client prefers among a server-supported
list — `HttpAcceptEncoding.recommend(List<String>) -> Optional<String>` — but `COMPRESS` offers only
gzip and discards the chosen token.

Relevant code: `model/server/CompressionPolicy`, `LocalHttpRequestStage.ResponseWriterImpl`,
`http2/Http2ServerStage.Http2ResponseWriter` + `doDispatch`, `utils/HttpAcceptEncoding`,
`utils/HttpContentType`. (No `ARCHITECTURE.md` yet.)

## Design

### 1. A shared `CompressingResponseWriter` decorator

Add `de.ofahrt.catfish.http.CompressingResponseWriter implements HttpResponseWriter`, wrapping a
delegate writer plus the request and policy:

```java
public CompressingResponseWriter(HttpResponseWriter delegate, HttpRequest request, CompressionPolicy policy);
```

Compression is cross-cutting and both stages already build an `HttpResponseWriter`, so it is applied
by wrapping at construction. The decorator operates purely on the shared `HttpResponse` /
`OutputStream` abstraction, so it works identically for both protocols and the per-protocol writers
stop knowing about gzip. Placement is the `http` package (concrete HTTP machinery, alongside the
response generators); it needs a new BUILD edge `http → model/server` (no cycle — `model/server` does
not depend on `http`).

### 2. `Coding` with an `IDENTITY` null-object, negotiated once

```java
private enum Coding {
  IDENTITY { /* returns its inputs untouched */ },
  GZIP { /* Content-Encoding: gzip + Vary; GZIPOutputStream / gzip(byte[]) */ };
  // DEFLATE { ... } is the entire future change
  abstract HttpResponse buffered(HttpResponse r) throws IOException;   // headers + encoded body
  abstract HttpResponse streamedHeaders(HttpResponse r);              // headers only
  abstract OutputStream wrap(OutputStream out) throws IOException;    // stream wrapper
}
```

`negotiate(response)` returns a **non-null** `Coding` (so no `@Nullable`, which matters under
NullAway): `IDENTITY` if the response already has a `Content-Encoding`, has no `Content-Type`, is not
policy-worthy, or the client accepts none of `SERVER_CODINGS`; otherwise the negotiated coding:

```java
private static final List<String> SERVER_CODINGS = List.of("gzip"); // add "deflate" later
...
if (!policy.shouldCompress(request, mimeType)) return Coding.IDENTITY;
String accept = request.getHeaders().get(HttpHeaderName.ACCEPT_ENCODING);
if (accept == null) return Coding.IDENTITY;
return HttpAcceptEncoding.parse(accept).recommend(SERVER_CODINGS).map(Coding::forToken).orElse(Coding.IDENTITY);
```

`IDENTITY` is a **true identity** — it returns the response/stream unchanged — so a body-less response
(204/304, no `Content-Type`) is passed through untouched. The commit methods then carry no nulls and
no branches:

```java
public void commitBuffered(HttpResponse r) throws IOException { delegate.commitBuffered(negotiate(r).buffered(r)); }
public OutputStream commitStreamed(HttpResponse r) throws IOException {
  Coding c = negotiate(r);
  return c.wrap(delegate.commitStreamed(c.streamedHeaders(r)));
}
public void abort() { delegate.abort(); }
```

### 3. `CompressionPolicy` becomes a pure worthiness gate

Negotiation moves to the decorator, so `CompressionPolicy.COMPRESS` drops its `HttpAcceptEncoding`
block and is left with the content-type whitelist check only. The interface signature
`boolean shouldCompress(HttpRequest request, String mimeType)` is **unchanged** — `request` is now
unused by the built-ins but kept so custom policies may still gate on request attributes (path,
user-agent). `application/json` is added to the whitelist. Net behaviour for gzip is identical: the
decorator performs the same accept-encoding check the policy used to.

### 4. Wiring (construction sites only)

- HTTP/1.1, `HttpServerStage.applyRoutingDecision` (ServeLocally): wrap the `ResponseWriterImpl` in a
  `CompressingResponseWriter`, and **delete** `ResponseWriterImpl`'s `shouldCompress`, both gzip
  blocks, and its `compressionPolicy` field.
- HTTP/2, `Http2ServerStage.doDispatch`: wrap the `Http2ResponseWriter` (inside the existing
  `NotifyingWriter`) in a `CompressingResponseWriter` built from the dispatched `request` and
  `serve.compressionPolicy()`. This is the actual bug fix.

## Security Considerations

- **Compression side channels (BREACH/CRIME):** enabling response compression on TLS responses that
  mix a secret (e.g. a CSRF token) with attacker-influenced reflected input is the classic BREACH
  precondition, and adding `application/json` slightly widens the exposed surface (JSON APIs). This is
  an embedder risk, not a regression: compression is **opt-in** (`HttpVirtualHost` defaults to
  `CompressionPolicy.NONE`), and this spec adds no secret-mixing that did not already exist on the
  HTTP/1.1 path. We do not implement length randomization; the trade-off is the embedder's, and is
  called out here and in the README compression note.
- **No decompression risk:** this is response *encoding* (we produce gzip), not decoding of untrusted
  input; decompression-bomb concerns (spec 0002) do not apply.
- **NIO thread:** `commitBuffered`/`commitStreamed` run on the executor thread (as the HTTP/1.1 writer
  does today), so gzip work never runs on the NIO selector thread. Buffered compression is bounded by
  the response body size; streamed compression is incremental as the handler writes.
- **Framing correctness:** `negotiate` returns `IDENTITY` when a `Content-Encoding` is already present, so
  a coding is never applied twice; `Content-Length` is recomputed from the compressed bytes on the
  buffered path, and the streamed path emits no `Content-Length` (HTTP/1.1 chunked, HTTP/2 DATA), so
  no length/body mismatch is introduced. `Vary: Accept-Encoding` is set whenever a coding is applied.

## Decisions

- **Decision:** Response compression lives in one `HttpResponseWriter` **decorator**, not copied into
  each protocol writer. — *Rationale:* it is cross-cutting and both stages already build a writer; a
  decorator gives a single implementation and makes the per-protocol writers compression-agnostic,
  which is precisely what the HTTP/2 gap was.
- **Decision:** The decorator lives in the `http` package, not `model/server`. — *Rationale:* it is
  concrete HTTP machinery (like the response generators), and `model/server` should stay policy/API.
  The new `http → model/server` BUILD edge introduces no cycle.
- **Decision:** Model "no compression" as a `Coding.IDENTITY` null-object rather than a nullable
  result, named for the HTTP `identity` content-coding. — *Rationale:* `negotiate` stays non-null
  (NullAway-friendly), the commit methods become branch-free and null-free, and `identity` is a
  legitimate HTTP content-coding, so it is honest, not a hack. It must be a true identity so body-less
  responses pass through untouched.
- **Decision:** Accept-encoding negotiation moves out of `CompressionPolicy` into the decorator,
  leaving the policy a pure content-type worthiness gate. — *Rationale:* worthiness (a property of the
  resource) and negotiation (a property of the client/transport) are orthogonal; conflating them is
  why adding a coding or the h2 path was awkward. `HttpAcceptEncoding.recommend` already returns the
  chosen coding, so the decorator owns "what can the client take, in what coding".
- **Decision:** Keep the `shouldCompress(HttpRequest, String)` signature even though the built-ins no
  longer read `request`. — *Rationale:* unlike `UploadPolicy`'s dual methods, an unused parameter is
  not a hazard, and it preserves request-based gating for custom policies; not worth a breaking change.
- **Decision:** gzip only now; `deflate` deferred (trivial follow-up), `br`/`zstd` a later spec. —
  *Rationale:* gzip is the parity fix and needs no dependency; `deflate` is one enum case under this
  design; `br`/`zstd` require a native library.

## Open Questions

None.

## Acceptance Criteria

- [ ] An HTTP/2 `GET` for a whitelisted content type with `accept-encoding: gzip` receives a
      `content-encoding: gzip` body that decodes to the handler's bytes; without `accept-encoding` it
      receives the uncompressed body.
- [ ] HTTP/1.1 compression behaviour is unchanged (same responses compressed, same headers) — verified
      by the existing `CompressionIntegrationTest` staying green after `ResponseWriterImpl` loses its
      gzip code.
- [ ] Both protocols compress identically for the same request/response (buffered and streamed).
- [ ] A response that already carries `Content-Encoding` is not re-compressed; a body-less response
      (204/304) is passed through byte-for-byte unchanged with no `Content-Encoding`/`Vary` added
      (the `Coding.IDENTITY` pass-through).
- [ ] `Vary: Accept-Encoding` is present whenever a coding is applied, on both protocols.
- [ ] `application/json` and `application/json; charset=utf-8` are compressed by
      `CompressionPolicy.COMPRESS`; a non-whitelisted type (e.g. `image/png`) is not.
- [ ] `CompressionPolicy.NONE` yields no compression on either protocol.
- [ ] Tests: unit tests for `CompressingResponseWriter` (negotiate → IDENTITY/GZIP across the gate
      conditions; buffered + streamed; identity pass-through); an h2 integration test for gzipped and
      non-gzipped responses; the h1 `CompressionIntegrationTest` unchanged.
- [ ] `bazel test //...` green and `bazel run //:format.check` passes.

## Implementation Plan

- [ ] PR 1: Add `CompressingResponseWriter` (+`Coding{IDENTITY,GZIP}`, `negotiate`) in `http`; add the
      `http → model/server` BUILD edge; simplify `CompressionPolicy.COMPRESS` to the worthiness gate
      and add `application/json`; rewire HTTP/1.1 to construct via the decorator and delete
      `ResponseWriterImpl`'s gzip code. Behaviour-identical for HTTP/1.1. Unit tests + existing
      `CompressionIntegrationTest` green.
- [ ] PR 2: Wrap the HTTP/2 `Http2ResponseWriter` in `CompressingResponseWriter` at
      `Http2ServerStage.doDispatch` — the bug fix. h2 integration tests for gzipped/non-gzipped
      responses; README compression note mentions h2 parity and the BREACH opt-in caveat.

## Notes

- `deflate` follow-up: add `"deflate"` to `SERVER_CODINGS` and a `DEFLATE` enum case
  (`DeflaterOutputStream`); no interface or wiring change.
- Response-size gating and `br`/`zstd` are separate follow-ups if a consumer appears.

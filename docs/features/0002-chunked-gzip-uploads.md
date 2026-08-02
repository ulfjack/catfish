---
id: 0002
title: Uploads without Content-Length; chunked + gzipped request bodies
status: ready
owner: Ulf Adams
architecture_refs:
  - HTTP/1.1 request body handling (HttpServerStage, LocalHttpRequestStage)
  - HTTP/2 request body handling (Http2ServerStage)
  - Upload handling (UploadPolicy, chunked decoding)
---

# 0002 — Uploads without Content-Length; chunked + gzipped request bodies

## Summary

Accept `Content-Encoding: gzip` request bodies (instead of rejecting them with 415) and
transparently decode them for local handlers, and give `UploadPolicy` an explicit **decoded-body
byte limit** enforced incrementally — making the chunked + gzipped, no-Content-Length upload shape
`git` uses work, while closing the unbounded-buffering hole that chunked bodies already have today.

## Goals

- Accept request bodies with `Content-Encoding: gzip` (and `x-gzip`) instead of blanket-rejecting
  them with 415, and deliver the **decoded** body to the `HttpHandler`.
- Make the git scenario — `Transfer-Encoding: chunked` + `Content-Encoding: gzip`, no
  `Content-Length` — work end to end.
- Extend `UploadPolicy` so it governs a **maximum decoded body size**, enforced **incrementally** as
  the body streams in, for Content-Length, chunked, and gzipped bodies alike. No magic constant.
- Apply the limit consistently on both the HTTP/1.1 and HTTP/2 local-serve paths.

## Non-Goals

- `deflate` / `br` / `zstd` request-body encodings. gzip (`x-gzip` alias) covers git; other codecs
  can be a later spec.
- Response-side compression (already handled by `CompressionPolicy`).
- Streaming a partially-decoded body to handlers incrementally — handlers still receive a fully
  assembled `InMemoryBody`, consistent with today's `LocalHttpRequestStage`. (The *limit* is
  enforced incrementally; the *delivery* to the handler stays buffered.)
- Changing proxy/forward behaviour: forwarded requests pass the body through raw (including
  `Content-Encoding`) and continue to.

## Background / Context

`git` performs large fetch/push operations by POSTing request bodies that are **chunked**
(`Transfer-Encoding: chunked`, no `Content-Length`) and **gzip-compressed** (`Content-Encoding:
gzip`). Catfish rejects these today: `LocalHttpRequestStage.onHeaders` returns `415 Unsupported
Media Type` whenever the request carries **any** `Content-Encoding` header, so a gzip upload is
refused before the body is read.

Two limitations in the current upload model matter here:

1. **`UploadPolicy` only sees headers.** `UploadPolicy.isAllowed(HttpRequest)` is called once at
   header time; `SimpleUploadPolicy` just parses the `Content-Length` header. A chunked body has no
   `Content-Length`, so the only real defence is absent — and `LocalHttpRequestStage.bodyBuffer` (a
   `ByteArrayOutputStream`) accumulates the whole body **unboundedly**. So chunked uploads already
   have an unbounded-memory hole today, before gzip enters the picture.
2. A gzipped body adds decompression amplification on top: a small compressed body can expand
   enormously.

A constant byte cap would be inappropriate for a low-level HTTP library — the embedder must decide
the limit. Relevant code: `model/server/UploadPolicy`, `upload/SimpleUploadPolicy`,
`LocalHttpRequestStage` (onHeaders / onBodyData / onBodyComplete), `HttpServerStage.readBody`,
`Http2ServerStage` body accumulation, `ChunkedDecodingOutputStream`. (No `ARCHITECTURE.md` yet.)

## Design

### 1. `UploadPolicy` becomes a single decoded-body byte ceiling

Replace the header-time yes/no gate with one method returning a limit the body machinery enforces as
bytes arrive:

```java
public interface UploadPolicy {
  /**
   * Maximum number of decoded body bytes to accept; 0 rejects any body. Enforced incrementally as
   * the body streams in; excess -> 413.
   */
  long maxDecodedBytes(HttpRequest request);
}
```

The old `boolean isAllowed(HttpRequest)` is **removed**, not kept alongside the new method:
`isAllowed(req) == false` is exactly `maxDecodedBytes(req) == 0`, so keeping both would admit
contradictory states (`isAllowed` true but limit 0, or false but a positive limit) that the body
machinery would have to arbitrate. Collapsing to one concept — a ceiling — leaves no illegal states.

Rejecting on non-size grounds (e.g. content-type / `Content-Encoding`) is **not** this interface's
job and never was: the gzip/415 decision lives in `LocalHttpRequestStage.onHeaders`, orthogonal to
`UploadPolicy`. So the collapse tightens the interface's single responsibility rather than losing
expressiveness.

`UploadPolicy.DENY`/`ALLOW` and `SimpleUploadPolicy` implement it (`DENY` -> 0; `ALLOW` ->
`Long.MAX_VALUE`; `SimpleUploadPolicy` -> its `maxContentLength` returned **unconditionally**,
generalising that field from a header check into an enforced streaming ceiling and dropping the
`cl != null` guard that made a missing `Content-Length` — i.e. every chunked/gzip upload — an
automatic rejection). This is a **breaking interface change** for external `UploadPolicy`
implementors — acceptable and desirable (see Decisions); the two built-ins, `SimpleUploadPolicy`, and
both in-tree call sites (`LocalHttpRequestStage`, `Http2ServerStage`) are updated here.

### 2. Enforce the limit incrementally while streaming

The body-streaming loops (`HttpServerStage.readBody` for Content-Length and chunked;
`Http2ServerStage` DATA accumulation) already see each chunk of body bytes. Track a running
decoded-byte count against `maxDecodedBytes` and, the moment it is exceeded, stop and produce `413
Payload Too Large` — never buffer past the ceiling. For chunked bodies the count is of the
*decoded* (de-chunked) bytes; the existing `ChunkedBodyScanner`/`ChunkedDecodingOutputStream`
already separate framing from content.

### 3. Accept and decode gzip

- **`LocalHttpRequestStage.onHeaders`:** replace the unconditional 415 with: `gzip`/`x-gzip`
  (case-insensitive; `identity` ≡ absent) -> accept and mark for decode; any other encoding -> 415.
- **Decode after transfer-decoding:** after the existing chunked-decode step produces the raw entity
  bytes, if gzip was marked, decode through `GZIPInputStream`. Decoding is **bounded by the same
  `maxDecodedBytes` ceiling**, enforced during inflation — the decompression-bomb defence and the
  body-size limit are one mechanism. Strip `Content-Encoding` from the handler-visible request (it
  sees decoded bytes), matching the transparent chunked-decode contract.
- **Malformed / truncated gzip** -> `400 Bad Request`, cleanly (no hang, no exception leaking to the
  network layer).

## Security Considerations

- **Decompression bombs & unbounded buffering (the central risk):** enforced by a single mechanism —
  the policy's `maxDecodedBytes`, checked *incrementally* on every streamed chunk and during gzip
  inflation, aborting with `413` before allocating past the ceiling. This closes both the gzip-bomb
  vector and the pre-existing unbounded chunked-buffer hole. The embedder sets the limit; there is
  no built-in magic number.
- **Malformed / truncated gzip:** yields a clean `400`, never an unhandled exception or hung
  connection.
- **Framing / smuggling:** the parser already rejects requests setting both `Content-Length` and
  `Transfer-Encoding`, and only `chunked` is an accepted transfer-coding; `Content-Encoding` is
  orthogonal to framing and adds no smuggling surface.
- **NIO thread:** limit-checking and decode run over bounded buffers on the NIO thread (as body
  handling does today) — CPU-only, bounded by the ceiling, no I/O blocking.
- **Consistency across protocols:** the limit is enforced on both the HTTP/1.1 and HTTP/2
  local-serve paths so h2 is not a bypass.

## Decisions

- **Decision:** The decoded-body limit is **policy-driven** via `UploadPolicy.maxDecodedBytes`, not a
  constant. — *Rationale:* a low-level HTTP library must let the embedder set the ceiling; a
  hardcoded constant is wrong. It also generalises `SimpleUploadPolicy.maxContentLength` from a
  header-only check into an enforced streaming limit, fixing the existing chunked hole.
- **Decision:** `UploadPolicy` collapses to the **single** method `maxDecodedBytes`; the old
  `isAllowed` boolean is removed, not retained alongside it. — *Rationale:* `isAllowed == false` is
  exactly `maxDecodedBytes == 0`, so two methods encode one decision twice and admit contradictory
  states the machinery would have to arbitrate. Non-size rejection (content-type / `Content-Encoding`)
  already lives outside the policy (`LocalHttpRequestStage.onHeaders`), so nothing is lost.
- **Decision:** Replacing `isAllowed` with a non-defaulted `maxDecodedBytes` (breaking `UploadPolicy`)
  is accepted. — *Rationale:* the interface has two built-in impls and one in-tree impl
  (`SimpleUploadPolicy`), all updated here; a silent `default` that returned `Long.MAX_VALUE` would
  quietly re-open the unbounded-buffer hole for existing implementors, which is worse than a
  compile error that forces an explicit choice.
- **Decision:** The ceiling counts **decoded** bytes only; there is no separate cap on wire bytes
  received. — *Rationale:* a decoded ceiling checked *during* inflation aborts a gzip bomb before its
  decompressed size is committed, so the small-on-the-wire attack is already defeated by the one
  mechanism; a second wire-byte limit would add configuration surface without closing a distinct
  hole.
- **Decision:** The limit counts **decoded** bytes (post de-chunk, post gunzip) and is enforced
  incrementally. — *Rationale:* that is the memory the server actually commits and the number an
  embedder reasons about; enforcing it during streaming/inflation is what defeats bombs.
- **Decision:** Support only `gzip`/`x-gzip` (`identity` ≡ absent); other encodings still 415. —
  *Rationale:* gzip is what git uses; speculative codecs widen the surface with no consumer.
- **Decision:** Handlers receive the fully decoded body as `InMemoryBody`, `Content-Encoding`
  stripped. — *Rationale:* mirrors the existing transparent chunked-decode contract.

## Open Questions

None.

## Acceptance Criteria

- [ ] A `POST` with `Content-Encoding: gzip` and a gzipped body is accepted and a local
      `HttpHandler` receives the **decoded** bytes.
- [ ] The git shape — `Transfer-Encoding: chunked` + `Content-Encoding: gzip`, no `Content-Length` —
      is accepted, de-chunked, decompressed, and delivered decoded.
- [ ] `x-gzip` ≡ `gzip`; `identity` ≡ no encoding.
- [ ] An unknown `Content-Encoding` (e.g. `br`) still returns `415`.
- [ ] A body (Content-Length, chunked, or gzipped) whose decoded size exceeds the policy's
      `maxDecodedBytes` returns `413` and does not buffer past the ceiling — verified for a chunked
      body with no `Content-Length` (the previously-unbounded case) and for a gzip bomb.
- [ ] A malformed/truncated gzip body returns `400` and the connection is handled cleanly.
- [ ] The limit is enforced identically on the HTTP/2 local-serve path.
- [ ] `SimpleUploadPolicy(n)` accepts a body of `n` decoded bytes and rejects `n+1`, including for a
      chunked body with no `Content-Length`.
- [ ] Tests: raw-socket HTTP/1.1 integration for all of the above; a git-shape round-trip; an h2
      integration test for the limit; unit tests for the bounded gzip-decode helper (ceiling +
      truncation).
- [ ] `bazel test //...` green and `bazel run //:format.check` passes.

## Implementation Plan

- [ ] PR 1 (breaking): Replace `UploadPolicy.isAllowed` with `long maxDecodedBytes`; implement in
      `DENY` (0) / `ALLOW` (`Long.MAX_VALUE`) and `SimpleUploadPolicy` (return `maxContentLength`
      unconditionally, dropping the `cl != null` guard); migrate both call sites
      (`LocalHttpRequestStage:97`, `Http2ServerStage:506`) to a `maxDecodedBytes(req) == 0` header-time
      reject. No incremental enforcement yet — pure interface + impls + call-site migration, plus unit
      tests for the policy values (including that a chunked/no-`Content-Length` request is now allowed).
- [ ] PR 2: Enforce `maxDecodedBytes` incrementally in the HTTP/1.1 body path
      (`HttpServerStage.readBody` + `LocalHttpRequestStage`), covering Content-Length and chunked;
      map excess -> 413. Closes the unbounded chunked-buffer hole. Integration tests.
- [ ] PR 3: Accept `gzip`/`x-gzip` in `LocalHttpRequestStage.onHeaders`; add a bounded gzip-decode
      helper (bounded by `maxDecodedBytes`, typed too-large/malformed signals); decode in
      `onBodyComplete`, strip `Content-Encoding`, map ceiling -> 413 and malformed -> 400.
      Unit + integration tests incl. the git shape.
- [ ] PR 4: Apply the same limit enforcement to the HTTP/2 local-serve path (`Http2ServerStage`);
      h2 integration test. README note under "Design overview".

## Notes

- If profiling later shows decode is too much work on the selector thread, moving assembly+decode to
  the handler executor is a separate optimisation.
- Follow-up candidate: `deflate` request bodies, if a consumer appears.

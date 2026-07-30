---
id: 0002
title: Uploads without Content-Length; chunked + gzipped request bodies
status: ready
owner: Ulf Adams
architecture_refs:
  - HTTP/1.1 request body handling (HttpServerStage, LocalHttpRequestStage)
  - Upload handling (UploadPolicy, chunked decoding)
---

# 0002 — Uploads without Content-Length; chunked + gzipped request bodies

## Summary

Accept `Content-Encoding: gzip` request bodies (instead of rejecting them with 415) and, for
locally-served requests, transparently decode them — making the chunked + gzipped, no-Content-Length
upload shape that `git` fetch/push relies on work end to end.

## Goals

- Accept request bodies with `Content-Encoding: gzip` (and `x-gzip`) instead of blanket-rejecting
  them with 415.
- For locally-served requests, deliver the **decoded** body to the `HttpHandler`, so handlers see
  plaintext regardless of transfer/content encoding — mirroring how chunked decoding is already
  handled transparently.
- Make the specific git scenario — `Transfer-Encoding: chunked` + `Content-Encoding: gzip`, no
  `Content-Length` — work end to end.
- Keep this bounded and safe: honour the upload policy and guard against decompression-bomb
  resource exhaustion.

## Non-Goals

- `deflate` / `br` / `zstd` request-body encodings. gzip (and its `x-gzip` alias) covers git; other
  codecs can be a later spec.
- Response-side compression negotiation (already handled by `CompressionPolicy`).
- Streaming a partially-decoded body to handlers incrementally — handlers still receive a fully
  assembled `InMemoryBody`, consistent with today's `LocalHttpRequestStage`.
- Changing proxy/forward behaviour: forwarded requests already pass the body through raw (including
  its `Content-Encoding`), and should continue to.

## Background / Context

`git` performs large fetch/push operations by POSTing request bodies that are **chunked**
(`Transfer-Encoding: chunked`, no `Content-Length`) and **gzip-compressed**
(`Content-Encoding: gzip`). Catfish rejects these today: `LocalHttpRequestStage.onHeaders` returns
`415 Unsupported Media Type` whenever the request carries **any** `Content-Encoding` header, so a
gzip upload is refused before the body is ever read.

Chunked framing *itself* already works (see `ChunkedBodyIntegrationTest`), and requests without a
`Content-Length` are handled when they're chunked. The specific gap is **compressed request
bodies**: the server has no path to accept (and, for local handlers, decode) a gzipped body, so the
combination git relies on is dead on arrival. Relevant code: `LocalHttpRequestStage` (onHeaders /
onBodyComplete), `HttpServerStage.startBodyOrDispatch` / `readBody`, `ChunkedBodyScanner`,
`ChunkedDecodingOutputStream`, `UploadPolicy`. (No `ARCHITECTURE.md` yet.)

## Design

Decoding happens in two layers that already exist and compose:

1. **Transfer-Encoding (chunked)** is de-framed. `HttpServerStage.readBody` already scans chunked
   framing (`ChunkedBodyScanner`) and `LocalHttpRequestStage.onBodyComplete` already decodes the
   raw chunked bytes via `ChunkedDecodingOutputStream`. Unchanged.
2. **Content-Encoding (gzip)** is decompressed. This is the new layer, applied to the
   *transfer-decoded* bytes.

Concretely:

- **Stop rejecting `Content-Encoding` in `LocalHttpRequestStage.onHeaders`.** Replace the
  unconditional 415 with: if the encoding is `gzip`/`x-gzip` (case-insensitive, `identity` treated
  as absent), accept it and remember to decode; otherwise return 415 (unchanged for unknown codecs).
- **Decode in `onBodyComplete`.** After the existing chunked-decode step produces the raw entity
  bytes, if a gzip content-encoding was recorded, run the bytes through a `GZIPInputStream` into a
  **bounded** buffer, then hand the decoded bytes to the handler as `InMemoryBody`. The
  handler-visible request has its `Content-Encoding` header stripped (the body it sees is decoded),
  matching how chunked requests present today.
- **Body-presence and upload-policy checks** in `HttpServerStage.startBodyOrDispatch` already treat
  chunked-without-Content-Length as "has body", so no framing change is needed there. The upload
  policy (`UploadPolicy.isAllowed`) is still consulted on headers before any body is read.

Decompression-bomb guard: the decode loop enforces a hard **max decoded bytes** ceiling and returns
`413 Payload Too Large` if the gzip stream expands past it (see Decisions). Malformed/truncated gzip
yields a clean `400 Bad Request`.

## Security Considerations

- **Decompression bombs (central risk):** a small gzipped body can expand enormously. The decode
  loop MUST enforce a hard ceiling and abort with `413`, enforced *incrementally* — never allocate
  past the ceiling before checking. This is why the ceiling is a required acceptance criterion, not
  a nice-to-have.
- **Malformed / truncated gzip:** must produce a clean `400 Bad Request`, never an unhandled
  exception, connection hang, or leaked exception to the network layer.
- **Framing ambiguity / smuggling:** the parser already rejects requests setting both
  `Content-Length` and `Transfer-Encoding`, and only `chunked` is an accepted transfer-coding.
  `Content-Encoding` is orthogonal to framing and adds no smuggling surface.
- **NIO thread:** decode runs in `onBodyComplete` (NIO thread today for the buffered local path)
  over a bounded buffer — CPU-only, bounded by the ceiling, no I/O blocking.
- **Upload policy still gates:** `UploadPolicy.isAllowed` is consulted on headers before any body is
  read or decoded, so a `DENY` policy rejects gzipped uploads with `413` before decompression.

## Decisions

- **Decision:** The decoded-size ceiling is a conservative internal **constant** for this spec, not
  policy-driven. — *Rationale:* `UploadPolicy` today only sees request headers, not the decoded
  stream; threading a size budget through it is a larger API change than this fix warrants. A
  constant closes the decompression-bomb hole now. A follow-up spec can make it policy-driven
  (a defaulted `UploadPolicy.maxDecodedBytes()` would be source/binary compatible) if a real
  deployment needs tuning. This keeps the change internal to `LocalHttpRequestStage` /
  `HttpServerStage` with **no public API impact**.
- **Decision:** Support only `gzip`/`x-gzip` (with `identity` ≡ absent); any other
  `Content-Encoding` still returns 415. — *Rationale:* gzip is what git uses; adding `deflate`/`br`
  speculatively widens the attack/maintenance surface with no current consumer.
- **Decision:** Handlers receive the fully decoded body as `InMemoryBody`, with `Content-Encoding`
  stripped. — *Rationale:* mirrors the existing transparent chunked-decode contract; handlers never
  deal with transfer/content framing.

## Open Questions

None.

## Acceptance Criteria

- [ ] A `POST` with `Content-Encoding: gzip` and a gzipped body (with `Content-Length`) is
      accepted, and a local `HttpHandler` receives the **decoded** body bytes.
- [ ] A `POST` with `Transfer-Encoding: chunked` **and** `Content-Encoding: gzip` and **no**
      `Content-Length` — the git shape — is accepted, de-chunked, decompressed, and the handler
      receives the fully decoded body.
- [ ] `x-gzip` is treated as `gzip`; `identity` is treated as no encoding.
- [ ] An unknown/unsupported `Content-Encoding` (e.g. `br`) still returns `415 Unsupported Media
      Type`.
- [ ] A gzipped body that decodes past the configured ceiling returns `413 Payload Too Large` and
      does not exhaust memory.
- [ ] A malformed/truncated gzip body returns `400 Bad Request` and the connection is handled
      cleanly (no hang, no leaked exception to the network layer).
- [ ] The upload policy is still consulted (a `DENY` policy still rejects a gzipped upload with
      `413` before decoding).
- [ ] Tests: raw-socket integration covering all of the above; a git-shape end-to-end round-trip;
      unit tests against the decode helper for the ceiling and malformed-gzip paths.
- [ ] Existing chunked and upload tests pass unchanged; `bazel test //...` green and
      `bazel run //:format.check` passes.

## Implementation Plan

- [ ] PR 1: A bounded gzip-decode helper (decode `byte[]` → `byte[]` with a max-output ceiling;
      throws a typed "too large" and a typed "malformed" signal). Unit-tested directly for the
      ceiling and truncation paths.
- [ ] PR 2: Wire it into `LocalHttpRequestStage`: accept `gzip`/`x-gzip` in `onHeaders`, decode in
      `onBodyComplete` after chunked-decode, strip `Content-Encoding`, map ceiling→413 and
      malformed→400. Integration tests for criteria 1–7.
- [ ] PR 3: README note under "Design overview" that gzipped request bodies are accepted and
      transparently decoded, and that chunked+gzip (git) is supported.

## Notes

- If profiling later shows decode is too much work on the selector thread, moving assembly+decode to
  the handler executor is a separate optimisation.
- Follow-up: policy-driven ceiling (see Decisions and Security Considerations).

# 0002 — Uploads without Content-Length; chunked + gzipped request bodies

- **Status:** Draft
- **Author(s):** agent (with Ulf Adams)
- **Created:** 2026-07-30
- **Related:** `LocalHttpRequestStage`, `HttpServerStage`, `ChunkedBodyScanner`,
  `ChunkedDecodingOutputStream`; README "Design overview"

## Problem

`git` performs large fetch/push operations by POSTing request bodies that are **chunked**
(`Transfer-Encoding: chunked`, no `Content-Length`) and **gzip-compressed**
(`Content-Encoding: gzip`). Catfish rejects these today:

- `LocalHttpRequestStage.onHeaders` returns **`415 Unsupported Media Type`** whenever the request
  carries **any** `Content-Encoding` header. So a `Content-Encoding: gzip` upload is refused before
  the body is ever read — git-over-HTTP against Catfish fails.

Chunked framing *itself* already works (see `ChunkedBodyIntegrationTest`), and requests without a
`Content-Length` are handled when they're chunked. The specific gap is **compressed request
bodies**: the server has no path to accept (and, for local handlers, decode) a gzipped body, so the
combination git relies on — chunked transfer of a gzipped payload — is dead on arrival.

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

## Non-goals

- `deflate` / `br` / `zstd` request-body encodings. gzip (and its `x-gzip` alias) covers git; other
  codecs can be a later spec.
- Response-side compression negotiation (already handled by `CompressionPolicy`).
- Streaming a partially-decoded body to handlers incrementally — handlers still receive a fully
  assembled `InMemoryBody`, consistent with today's `LocalHttpRequestStage`.
- Changing proxy/forward behaviour: forwarded requests already pass the body through raw
  (including its `Content-Encoding`), and should continue to.

## Approach

The decode happens in two layers that already exist and compose:

1. **Transfer-Encoding (chunked)** is de-framed. `HttpServerStage.readBody` already scans chunked
   framing (`ChunkedBodyScanner`) and `LocalHttpRequestStage.onBodyComplete` already decodes the
   raw chunked bytes via `ChunkedDecodingOutputStream`. This is unchanged.

2. **Content-Encoding (gzip)** is decompressed. This is the new layer, applied to the
   *transfer-decoded* bytes.

Concretely:

- **Stop rejecting `Content-Encoding` in `LocalHttpRequestStage.onHeaders`.** Replace the
  unconditional 415 with: if the encoding is `gzip`/`x-gzip` (case-insensitive, `identity` treated
  as absent), accept it and remember to decode; otherwise return 415 (unchanged for unknown
  codecs).
- **Decode in `onBodyComplete`.** After the existing chunked-decode step produces the raw entity
  bytes, if a gzip content-encoding was recorded, run the bytes through a `GZIPInputStream` into a
  bounded buffer, then hand the decoded bytes to the handler as `InMemoryBody`. The handler-visible
  request should have its `Content-Encoding` header stripped (the body it sees is decoded) —
  confirm this matches how chunked requests present today.
- **Body-presence and upload-policy checks in `HttpServerStage.startBodyOrDispatch` already treat
  chunked-without-Content-Length as "has body"**, so no framing change is needed there. The
  upload policy (`UploadPolicy.isAllowed`) is still consulted on headers before any body is read.

Decompression-bomb guard: cap the decoded size. The natural cap is the same budget the upload
policy expresses; since `UploadPolicy` today only sees headers, add an explicit **max decoded
bytes** ceiling in the decode loop and return `413 Payload Too Large` if the gzip stream expands
past it. **Open question for review:** where the ceiling comes from — a constant, a value derived
from `SimpleUploadPolicy`, or a new method on `UploadPolicy`. Leaning toward a conservative
constant for this spec, with a follow-up to make it policy-driven.

## Public API impact

- **Likely none** for the minimal version (a constant ceiling): the change is internal to
  `LocalHttpRequestStage` / `HttpServerStage`.
- **Possible additive change** if we make the decoded-size ceiling policy-driven: a new
  default-methoded interface member on `UploadPolicy` (e.g. `long maxDecodedBytes()` with a
  sensible default), which keeps existing implementations source/binary compatible. Decide in
  review.
- **README:** note that gzipped request bodies are accepted and transparently decoded for local
  handlers, and that chunked+gzip (the git case) is supported.

## Security & correctness considerations

- **Decompression bombs:** a small gzipped body can expand enormously. The decode loop MUST enforce
  a hard ceiling and abort with `413` rather than allocating unbounded memory. This is the central
  risk and the reason the ceiling is a required acceptance criterion, not a nice-to-have.
- **Framing ambiguity / smuggling:** the parser already rejects requests that set both
  `Content-Length` and `Transfer-Encoding` (`IncrementalHttpRequestParser.validateAndFinish`), and
  only `chunked` is an accepted transfer-coding. `Content-Encoding` is orthogonal to framing and
  does not reintroduce a smuggling surface.
- **Malformed gzip:** a truncated or corrupt gzip stream must produce a clean `400 Bad Request`
  (not an unhandled exception / connection hang).
- **NIO thread:** decoding happens in `onBodyComplete`, which runs on the NIO thread today for the
  buffered local path; decoding a bounded buffer is CPU-only and bounded by the ceiling, so it does
  not block on I/O. (If profiling later shows this is too much work on the selector thread, moving
  assembly+decode to the handler executor is a separate optimisation.)
- **Empty / `identity` encoding:** treated as no content-encoding; no behaviour change.

## Acceptance criteria

1. A `POST` with `Content-Encoding: gzip` and a gzipped body (with `Content-Length`) is accepted,
   and a local `HttpHandler` receives the **decoded** body bytes.
2. A `POST` with `Transfer-Encoding: chunked` **and** `Content-Encoding: gzip` and **no**
   `Content-Length` — the git shape — is accepted, de-chunked, decompressed, and the handler
   receives the fully decoded body.
3. `x-gzip` is treated as `gzip`; `identity` is treated as no encoding.
4. An unknown/unsupported `Content-Encoding` (e.g. `br`) still returns `415 Unsupported Media
   Type`.
5. A gzipped body that decodes past the configured ceiling returns `413 Payload Too Large` and does
   not exhaust memory.
6. A malformed/truncated gzip body returns `400 Bad Request` and the connection is handled cleanly
   (no hang, no leaked exception to the network layer).
7. The upload policy is still consulted (a `DENY` policy still rejects a gzipped upload with `413`
   before decoding).
8. Existing chunked and upload tests continue to pass unchanged; `bazel test //...` green and
   `bazel run //:format.check` passes.

## Testing plan

- **Integration (`ChunkedBodyIntegrationTest` sibling / extension):** raw-socket requests covering
  criteria 1–7, asserting the handler-observed body and the status codes. Reuse the
  `SerializableHttpServletRequest` echo path already used by `ChunkedBodyIntegrationTest`.
- **git-shape end-to-end:** a test that constructs the exact chunked-gzip framing git emits and
  asserts the decoded body round-trips.
- **Unit:** decode-ceiling and malformed-gzip behaviour tested directly against the decode helper
  (fast, deterministic), so the bomb/truncation paths aren't only covered through sockets.
- **Regression:** `ChunkedBodyIntegrationTest`, `SimpleUploadPolicyTest`, `DenyUploadPolicyTest`
  unchanged.

## Rollout / compatibility notes

Backwards compatible: requests that previously got `415` for `Content-Encoding: gzip` now succeed;
no currently-accepted request changes behaviour. If the ceiling becomes policy-driven, existing
`UploadPolicy` implementations keep working via a defaulted method. Embedders serving untrusted
clients get a safer default (bounded decode) than the current outright rejection.

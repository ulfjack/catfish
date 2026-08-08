---
id: 0007
title: Consolidate chunked transfer-coding onto one strict state machine
status: ready
owner: Ulf Adams
architecture_refs:
  - HTTP/1.1 request body framing (HttpServerStage, ChunkedBodyScanner)
  - Chunked decode (LocalHttpRequestStage, ChunkedDecodingOutputStream)
  - Proxy body handling (OriginForwarder)
  - Chunked body SPI (upload/ChunkedBodyParser)
---

# 0007 — Consolidate chunked transfer-coding onto one strict state machine

## Summary

Replace the three independent, non-identical chunked transfer-coding parsers with a **single strict
state machine** plus thin adapters, and make it **reject any framing that deviates from the RFC 9112
grammar** (notably: bare LF or lone CR anywhere, and non-hex characters in the chunk-size field)
rather than silently normalising it. This removes a request-smuggling / framing-ambiguity surface
that exists today because the parser that decides where a body *ends* (and thus where the next
pipelined request begins) is more lenient than — and disagrees with — the parser that *decodes* the
body.

## Goals

- One place in the codebase defines the chunked grammar. The boundary scanner, the streaming
  decoder, and the in-memory SPI parser are all thin adapters over it.
- The grammar is **strict**: malformed framing is rejected (→ `400`, connection closed), never
  accepted-and-normalised. Specifically, line endings must be exactly CRLF.
- The boundary the scanner reports (end of body = start of next request) is, by construction,
  identical to what the decoder consumes — because they are the same machine.
- No behavioural regression for well-formed chunked bodies (data, chunk extensions, trailers,
  bulk-skip performance on the NIO thread, the `findEnd` dry-run, and the decoded-byte counter used
  by the spec 0002 size ceiling all preserved).

## Non-Goals

- **Reframing chunked bodies on proxy forwarding** (decode + re-encode canonical chunks to the
  origin). This is a separate, larger change with its own trade-offs (trailer handling, request- vs
  response-side scope) and will get its own spec. This spec only *unifies and hardens* the parsers;
  the forward proxy continues to pass the raw chunked body through — now delimited by the strict
  scanner.
- Hardening `Content-Length` parsing (it currently accepts `+`/leading zeros via `Long.parseLong`).
  Different code path, not chunked framing; tracked as a separate follow-up.
- HTTP/2, which has no chunked transfer-coding.
- Changing the `HttpRequestBodyParser` SPI shape or the `UploadPolicy` size-limit contract from
  spec 0002.

## Background / Context

Chunked framing is currently implemented **three times**, with different rules:

1. `http/ChunkedBodyScanner` — runs on the NIO thread; scans (does not decode) to find where the
   body ends so the bytes after it stay buffered as the next request. Wired into
   `HttpServerStage` (request bodies) and `OriginForwarder` (proxied response bodies). Exposes the
   non-mutating `findEnd` dry-run and `decodedByteCount()` (the spec 0002 ceiling).
2. `http/ChunkedDecodingOutputStream` — an `OutputStream` filter that strips framing and forwards
   decoded bytes. Wired into `LocalHttpRequestStage` (decode the buffered request body) and
   `OriginForwarder` (the capture/cache tee).
3. `upload/ChunkedBodyParser` — an incremental `HttpRequestBodyParser` that accumulates the decoded
   body in memory. **Not wired into any server path** (only its own tests construct it), yet it is
   the *strictest and best-tested* of the three.

The security-relevant divergences (see the review that motivated this spec):

- **(1) and (2) silently drop invalid characters in the chunk-size field** — no `else` branch in
  their `SIZE` state (`ChunkedBodyScanner.java:97`, `ChunkedDecodingOutputStream.java:46`). So
  `5 junk\r\n`, `0x5\r\n`, embedded whitespace, etc. all parse as size `5`. (3) rejects these
  (`ChunkedBodyParser.java:52-61`).
- **Line-ending strictness is inconsistent.** (3) accepts a bare LF as a line terminator (it has
  passing tests for it); (1) and (2) require CR and *ignore* a lone LF. (1) and (2) also silently
  tolerate junk between chunk-data and its CRLF (their `DATA_CR`/`DATA_LF` states ignore
  unexpected bytes), and disagree with each other on the terminal `0\r\n\r?` sequence
  (`ChunkedBodyScanner.java:169` requires the final `\n`; `ChunkedDecodingOutputStream.java:108`
  goes to `DONE` unconditionally).

Because Catfish is both a server and a forward proxy, a lenient boundary that "normalises" malformed
framing is exactly the ambiguity that lets a request be framed one way by an upstream/peer and
another way by Catfish — request smuggling. The fix is to have one machine, and to make it refuse
ambiguity outright.

Relevant code: `http/ChunkedBodyScanner`, `http/ChunkedDecodingOutputStream`,
`upload/ChunkedBodyParser`, `HttpServerStage.readBody`, `LocalHttpRequestStage.onBodyComplete`,
`OriginForwarder.streamChunkedBody`. (No `ARCHITECTURE.md` yet.)

## Design

### One core machine

Introduce a single core state machine in `http` (`ChunkedBodyState`). It advances over a byte range,
reporting decoded content through a caller-supplied **sink** that receives zero-copy spans pointing
into the caller's buffer (not byte-at-a-time). `advance` returns how many input bytes it consumed —
the full range while the body continues, the count up to and including the terminal CRLF once the
body ends, or the count up to the offending byte on error — and latches `isDone()` / `hasError()`
accordingly. It bulk-skips chunk-data (no per-byte loop), exposes the running de-chunked byte total
(`decodedByteCount()`, for the spec 0002 ceiling), and supports a cheap `copy()` for the scanner's
non-mutating `findEnd` dry-run, plus `reset()` for connection reuse.

The three existing classes stay (same public API and call sites) but become thin adapters:

- **`ChunkedBodyScanner`** wraps a `ChunkedBodyState` and passes a no-op `Sink`. `advance`/`isDone`/
  `hasError`/`decodedByteCount`/`reset` delegate. `findEnd` runs `advance` on `copy()` with the no-op
  sink and returns the end position iff the copy reached `isDone()` — no hand-rolled save/restore of
  a dozen fields (the current source of drift risk).
- **`ChunkedDecodingOutputStream`** drives the core with a `Sink` that writes the span to the
  delegate `OutputStream`. On `hasError()` it throws `IOException` (malformed body), instead of
  today's silent tolerance.
- **`upload/ChunkedBodyParser`** drives the core with a `Sink` that appends to its
  `ByteArrayOutputStream`; `hasError()` → the `IOException` its `getParsedBody()` already contracts.
  This adds an `upload → http` Bazel dependency (upload currently depends only on model/utils; the
  edge is acyclic — `http` does not depend on `upload`).

### The strict grammar (RFC 9112 §7.1), defined once

- **chunk-size** = `1*HEXDIG`. At least one hex digit required. Any byte in the size field that is
  not a hex digit, `;` (start of chunk-ext), or CR → **error**. Reject a decoded size `> Integer.MAX_VALUE`
  (bodies are int-bounded elsewhere; also guards long overflow).
- **chunk-ext** = after `;`, extension bytes are skipped up to the line's CR (content not validated),
  but the line **must** terminate with CRLF.
- **line endings are exactly CRLF, everywhere**: after the chunk-size/ext line, after chunk-data,
  between trailer lines, and the terminal CRLF. A CR **must** be immediately followed by LF; a bare
  LF (no preceding CR) is an **error**. No exceptions.
- **chunk-data** = exactly chunk-size bytes, then CRLF. Any non-CR byte where CRLF is expected →
  error.
- **last-chunk / trailers** = a `0` chunk, then zero or more `field-line CRLF` trailer lines
  (parsed only far enough to skip; discarded), then the terminal CRLF. The trailer section is
  bounded (see Security) → error past the bound.
- On any error the machine latches `hasError()` and stops. `HttpServerStage` already maps
  `scanner.hasError()` to `400` + close (`HttpServerStage.java:475`); there is no keep-alive resume
  after a framing error (no safe boundary to resume from).

## Security Considerations

- **Request smuggling / framing ambiguity (the central risk):** a single machine means the "where
  does the body end" boundary and the "what are the decoded bytes" decode are provably the same
  interpretation — the scanner/decoder divergence class is eliminated. Strict, reject-don't-normalise
  parsing means Catfish refuses any framing a peer might resolve differently (bare LF, junk in the
  size field, junk around chunk-data) rather than laundering it into clean framing. This is the
  strongest ingress posture; it does not by itself address the *egress* (forward-proxy → origin)
  hop, which is what the deferred reframing spec covers.
- **Behavioural change — stricter parsing may reject clients today's lenient parsers accept.** This
  is intentional and the point of the change; it is standards-conformant (RFC 9112 permits, but does
  not require, bare-LF tolerance, and a security-conscious server/proxy should be strict). Recorded
  as a Decision; the affected `ChunkedBodyParserTest` bare-LF cases flip to expecting errors.
- **Resource exhaustion:** the decoded-byte ceiling from spec 0002 is preserved
  (`decodedByteCount()`). Chunk-size is capped (`≤ Integer.MAX_VALUE`, ≤15 hex digits). The trailer
  section gains an explicit byte bound so a peer cannot stall a connection in the trailer state
  indefinitely.
- **NIO thread:** the core is CPU-only over bounded buffers, retains chunk-data bulk-skip, never
  blocks — same thread-model guarantees as today's scanner.

## Decisions

- **Decision:** One core `ChunkedBodyState` defines the grammar; `ChunkedBodyScanner`,
  `ChunkedDecodingOutputStream`, and `ChunkedBodyParser` become thin adapters (no-op sink / stream
  sink / buffer sink). — *Rationale:* three hand-maintained state machines drifted apart into a
  security bug; one definition cannot disagree with itself.
- **Decision:** Line endings must be exactly CRLF; a bare LF or a CR not followed by LF is an error,
  in every state (size line, chunk-ext, chunk-data terminator, trailers, terminal CRLF). — *Rationale:*
  bare-LF tolerance is the classic chunked-smuggling lever; refusing it removes the ambiguity a
  differing peer could exploit. Flips `ChunkedBodyParserTest.chunkSizeBareLf`, `chunkExtBareLf`,
  `dataBareLf`, and `finalBareLf` to expect errors.
- **Decision:** Any non-hex, non-`;`, non-CR byte in the chunk-size field is an error (no silent
  skipping). — *Rationale:* silent skipping is precisely how the scanner/decoder accepted
  `5 junk\r\n` as size 5, an ambiguity a strict peer would frame differently.
- **Decision:** The decoded `Sink` receives **zero-copy spans** into the caller's buffer, not
  byte-at-a-time. — *Rationale:* preserves NIO-thread performance and the existing bulk-skip; a
  per-byte callback would regress the hot path.
- **Decision:** `findEnd` is implemented via `copy()` + no-op sink, not by saving/restoring the
  machine's fields in the adapter. — *Rationale:* the manual save/restore is itself a drift/bug
  surface; copying the small value is simpler and correct by construction.
- **Decision:** Keep `upload/ChunkedBodyParser` (re-backed by the core) rather than delete it, and
  add the `upload → http` Bazel edge. — *Rationale:* it is the `HttpRequestBodyParser` SPI's chunked
  implementation and carries the best test suite; keeping exactly one strict implementation behind it
  is the goal, and the new dependency edge is acyclic.
- **Decision:** The trailer section is explicitly bounded. — *Rationale:* today's scanner has no
  trailer bound; an unbounded trailer stream can hold a connection in the trailer state. A bound
  makes the terminal deterministic.

## Open Questions

None.

## Acceptance Criteria

- [ ] Exactly one state machine encodes the chunked grammar; `ChunkedBodyScanner`,
      `ChunkedDecodingOutputStream`, and `ChunkedBodyParser` contain no independent framing logic.
- [ ] A well-formed chunked body (single chunk, multiple chunks, chunk extensions, trailers, empty
      body) decodes identically to today, on the local-serve and proxied-response paths.
- [ ] The `findEnd` dry-run reports the same boundary the decoder consumes, verified on a pipelined
      request (`<chunked body><next request>`): the next request is parsed intact.
- [ ] Bare LF (no CR) in the chunk-size line, chunk-ext, after chunk-data, or in the terminal CRLF
      is rejected with `400` and the connection is closed — via raw-socket integration.
- [ ] A non-hex byte in the chunk-size field (`5 x\r\n`, `0x5\r\n`) is rejected with `400`.
- [ ] A chunk-size `> Integer.MAX_VALUE` or `>15` hex digits is rejected.
- [ ] A trailer section exceeding the bound is rejected with `400`.
- [ ] The spec 0002 decoded-body ceiling still fires (`413`) for an oversized chunked body with no
      `Content-Length`.
- [ ] `ChunkedBodyParserTest` updated: the four bare-LF cases now assert errors; all other cases
      still pass.
- [ ] `bazel test //...` green and `bazel run //:format.check` passes.

## Implementation Plan

- [ ] PR 1: Add `http/ChunkedBodyState` (the strict core) with `Sink`, `advance`, `copy`,
      `decodedByteCount`, `isDone`/`hasError`, `reset`. Unit tests covering the full grammar and every
      rejection case (bare LF in each state, non-hex size, oversize, junk-around-data, trailer bound,
      pipelined-boundary reporting). No call sites changed yet.
- [ ] PR 2: Reimplement `ChunkedBodyScanner` (no-op sink; `findEnd` via `copy()`) and
      `ChunkedDecodingOutputStream` (stream sink; throw on `hasError`) over the core. Keep their
      public APIs. Update/confirm `HttpServerStage`, `LocalHttpRequestStage`, `OriginForwarder`
      behaviour and their tests; add the pipelined-boundary and malformed-framing integration tests.
- [ ] PR 3: Reimplement `upload/ChunkedBodyParser` over the core; add the `upload → http` Bazel dep;
      flip the four bare-LF test expectations. (PR 2 and PR 3 may land together if review prefers.)

## Notes

- This spec is the prerequisite for the deferred **chunked reframing on proxy** work: reframing is
  only safe on top of a single strict machine (so the inbound boundary, the decoded body, and any
  re-emitted framing are guaranteed to agree). That work gets its own spec once the shape is decided.
- Follow-up candidate: harden `Content-Length` parsing to `1*DIGIT` (reject `+`/leading zeros)
  rather than relying on `Long.parseLong` — a distinct framing-ambiguity finding from the same review.

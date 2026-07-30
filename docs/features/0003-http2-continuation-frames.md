# 0003 — HTTP/2 CONTINUATION frame support

- **Status:** Draft
- **Author(s):** agent (with Ulf Adams)
- **Created:** 2026-07-30
- **Related:** RFC 9113 §6.2 (HEADERS), §6.10 (CONTINUATION); `Http2ServerStage`,
  `Http2FrameReader`, `Http2FrameWriter`, `HpackDecoder`; spec 0001 (ALPN, unrelated but same h2
  stack). CVE-2024-27316 (CONTINUATION flood), CVE-2023-44487 (Rapid Reset, already mitigated).

## Problem

Catfish does not support HTTP/2 CONTINUATION frames, in either direction:

- **Inbound:** `Http2ServerStage.handleHeaders` throws a connection error if a HEADERS frame lacks
  the `END_HEADERS` flag, and `processFrame` throws on any `CONTINUATION` frame ("not supported").
  Per RFC 9113 §6.2, when an encoded header block does not fit in a single HEADERS frame the sender
  **must** split it: one HEADERS frame without `END_HEADERS`, followed by one or more CONTINUATION
  frames, the last carrying `END_HEADERS`. A header block only has to exceed the max frame size
  (default 16 KB, and Catfish's `Http2FrameReader` hard-caps payloads at 16384 bytes) to require
  this. Real requests hit that: large cookies, long URLs/query strings, many or long headers, or a
  cold HPACK dynamic table. Against such a client Catfish kills the connection instead of serving
  the request — a correctness/interop failure with conformant peers.

- **Outbound:** `Http2FrameWriter.writeHeaders` always sets `END_HEADERS` and writes the entire
  pre-encoded HPACK block as a single HEADERS frame. If a *response* header block ever exceeds the
  peer's `SETTINGS_MAX_FRAME_SIZE`, Catfish emits an illegal oversized frame. Latent today (Catfish
  responses are small), but it's the same missing capability and should be fixed together.

## Goals

- **Receive** header blocks split across a HEADERS frame + one or more CONTINUATION frames,
  reassemble the fragments, HPACK-decode the whole, and process the request normally.
- **Send** response header blocks that exceed the peer's max frame size by splitting into
  HEADERS + CONTINUATION frame(s).
- Enforce the CONTINUATION-frame interleaving rules and the security bounds that make this safe
  (CVE-2024-27316).

## Non-goals

- PUSH_PROMISE (server push): disabled via `SETTINGS_ENABLE_PUSH = 0`; its CONTINUATION interaction
  is therefore out of scope.
- Trailers (a second HEADERS block after DATA). Catfish doesn't process request trailers today;
  this spec does not add them, but the reassembly logic must still correctly *skip/av reject* a
  trailer sequence the same way HEADERS is handled (see acceptance criteria).
- Raising the max frame size or max header list size. The existing
  `HpackDecoder.DEFAULT_MAX_HEADER_LIST_SIZE` (32 KB decoded) ceiling stays.

## Approach

### Inbound reassembly (the core change)

The frame reader (`Http2FrameReader`) stays frame-at-a-time; reassembly lives in
`Http2ServerStage`, which already drives `processFrame()` per complete frame. Introduce an explicit
**header-block assembly** state on the stage:

- Fields: `@Nullable` accumulation buffer (a growable byte sink), the `headerStreamId` the block
  belongs to, the saved HEADERS flags (`END_STREAM`, and the padding/priority already stripped),
  and a running byte count.
- `handleHeaders`: strip padding/priority as today to isolate the HPACK fragment. If `END_HEADERS`
  is set, decode immediately (unchanged fast path). If not, **stash** the fragment bytes and the
  stream id / flags, and enter "expecting CONTINUATION" mode — do **not** decode yet.
- `processFrame`: while in assembly mode, the **only** legal next frame is a `CONTINUATION` on the
  *same* stream id (RFC 9113 §6.10 / §6.2). Any other frame type, a CONTINUATION on a different
  stream, or a CONTINUATION when not in assembly mode is a `PROTOCOL_ERROR` connection error.
- `handleContinuation` (new): append the fragment; when `END_HEADERS` arrives, concatenate all
  fragments, HPACK-decode the whole block once, and run the existing pseudo-header extraction /
  request-building / routing path (factor that tail of `handleHeaders` into a shared
  `dispatchDecodedHeaderBlock(streamId, endStream, block)` method).

Because HPACK is stateful across the connection, decoding **must** happen only over the fully
reassembled block, in order — never per fragment. This is why partial decode is not an option.

### Outbound splitting

In `Http2Stream.writeResponseFrames` / `Http2FrameWriter`, when the encoded response header block
is larger than the effective max frame size, emit a HEADERS frame (no `END_HEADERS`) with the first
`maxFrameSize` bytes, then CONTINUATION frames for the remainder, the last with `END_HEADERS`. Add
`Http2FrameWriter.writeContinuation(...)` and teach the writer path to chunk. Respect the existing
buffer-space/`BLOCKED` backpressure protocol so a large header block spanning multiple frames can
be written across several `write()` calls without corrupting framing.

### Frame reader

`Http2FrameReader` needs no structural change for reassembly, but CONTINUATION must be a
first-class type it will parse (it already parses arbitrary types generically). Confirm a
CONTINUATION frame's payload is delivered like any other.

## Public API impact

**None.** This is entirely within the `de.ofahrt.catfish.http2` package (package-private classes).
No `model`/`server`/endpoint API changes. `HttpHandler` is unaffected — it still sees a fully
assembled `HttpRequest`. README's HTTP/2 paragraph may get a one-line note that large header blocks
are supported.

## Security & correctness considerations

This is the load-bearing section — CONTINUATION is a known DoS vector.

- **CONTINUATION flood (CVE-2024-27316):** an attacker sends a HEADERS frame without `END_HEADERS`
  followed by an unbounded stream of CONTINUATION frames (or many small fragments), forcing the
  server to buffer/decode without limit. Mitigations, all required:
  1. **Cap total accumulated (compressed) header-block bytes** per request at a fixed ceiling
     (proposal: a small multiple of `DEFAULT_MAX_HEADER_LIST_SIZE`, e.g. 2×32 KB = 64 KB of
     compressed fragments). Exceeding it is a connection error (`ENHANCE_YOUR_CALM` / GOAWAY),
     closing the connection rather than just resetting the stream.
  2. **Cap the number of CONTINUATION frames** per header block (e.g. a small constant), to bound
     tiny-fragment amplification even under the byte cap.
  3. The existing decoded-size ceiling (`maxHeaderListSize`, 32 KB) still applies after reassembly.
  4. Enforce caps **incrementally** as fragments arrive — never accumulate past the ceiling before
     checking.
- **Interleaving / state confusion (request smuggling surface):** while assembling a header block,
  no other frame may arrive (not DATA, not another HEADERS, not SETTINGS, not a CONTINUATION for a
  different stream). Enforce this strictly as a `PROTOCOL_ERROR`; a lax reader here is a framing
  vulnerability.
- **Stream-id validation** (odd, monotonic) is done once when the HEADERS frame opens the block, so
  CONTINUATION frames are validated against the in-progress stream id only.
- **NIO thread:** reassembly and decode are CPU-only over bounded buffers; no blocking is
  introduced. Backpressure on the write side uses the existing `BLOCKED` protocol.
- **GOAWAY during assembly:** if we've decided to GOAWAY, an in-progress header block is still
  consumed to keep HPACK dynamic-table state consistent (a half-decoded block would desync the
  table for the rest of the connection) but the resulting stream is not opened — mirror the
  existing post-GOAWAY "ignore new streams" behaviour.

## Acceptance criteria

1. A request whose HPACK header block is split across a HEADERS frame (no `END_HEADERS`) + one
   CONTINUATION frame (with `END_HEADERS`) is reassembled, decoded, and served with a correct
   response.
2. A block split across a HEADERS frame + **multiple** CONTINUATION frames is served correctly.
3. HPACK dynamic-table state stays correct across a reassembled block: a **subsequent** request on
   the same connection that references dynamic-table entries established by the reassembled block
   decodes correctly.
4. A CONTINUATION frame received when no header block is in progress is a connection error
   (`PROTOCOL_ERROR`).
5. Any non-CONTINUATION frame, or a CONTINUATION for a different stream, received while a header
   block is in progress is a connection error (`PROTOCOL_ERROR`).
6. Accumulated compressed header-block bytes exceeding the configured ceiling terminates the
   connection (GOAWAY) rather than buffering unboundedly.
7. Exceeding the configured maximum number of CONTINUATION frames for one block terminates the
   connection.
8. Outbound: a response whose encoded header block exceeds the peer's max frame size is written as
   a HEADERS frame (no `END_HEADERS`) followed by CONTINUATION frame(s), the last with
   `END_HEADERS`, and a conformant client reassembles it (verified via the JDK HTTP/2 client or a
   frame-level assertion).
9. Existing single-frame HEADERS request/response behaviour is unchanged (all current h2 tests pass
   unmodified).
10. `bazel test //...` green and `bazel run //:format.check` passes.

## Testing plan

- **Unit (`Http2ServerStageTest`):** drive the stage with hand-built HEADERS+CONTINUATION frame
  sequences for criteria 1–7, asserting the decoded request and the connection-error paths. This is
  the primary coverage — it exercises the state machine deterministically without a socket.
- **Unit (`Http2FrameWriterTest` / `Http2StreamTest`):** outbound splitting (criterion 8) at the
  frame level: force a small max frame size and assert the HEADERS/CONTINUATION framing and flags.
- **Integration (`Http2IntegrationTest`):** an end-to-end request with a header block large enough
  to force CONTINUATION (e.g. a large cookie or many headers) via the JDK `HttpClient`, asserting a
  200 and correct echo. Confirms real-client interop (criteria 1–3, 8).
- **HPACK continuity:** a two-request test where request 2's compression depends on request 1's
  reassembled headers (criterion 3).
- **Security:** targeted tests for the byte-cap and frame-count-cap connection errors (criteria
  6–7), using many small fragments.
- **Regression:** existing `AllTests` h2 suite must pass unmodified (criterion 9).

## Rollout / compatibility notes

Backwards compatible and strictly more conformant: requests that Catfish previously killed now
succeed, and no currently-accepted request changes behaviour. No embedder action required. The new
security ceilings are generous enough not to affect legitimate traffic but close the CONTINUATION
-flood vector. If the caps ever need tuning they are internal constants (candidate follow-up: make
them configurable if a real deployment needs it).

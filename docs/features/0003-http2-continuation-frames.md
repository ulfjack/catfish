---
id: 0003
title: HTTP/2 CONTINUATION frame support
status: ready
owner: Ulf Adams
architecture_refs:
  - HTTP/2 (Http2ServerStage, Http2FrameReader, Http2FrameWriter, HpackDecoder)
---

# 0003 — HTTP/2 CONTINUATION frame support

## Summary

Support HTTP/2 CONTINUATION frames in both directions — reassembling header blocks split across
HEADERS + CONTINUATION frames on receive, and splitting oversized response header blocks on send —
so Catfish stops killing connections whose header block exceeds one frame (default 16 KB).

## Goals

- **Receive** header blocks split across a HEADERS frame + one or more CONTINUATION frames,
  reassemble the fragments, HPACK-decode the whole, and process the request normally.
- **Send** response header blocks that exceed the peer's max frame size by splitting into
  HEADERS + CONTINUATION frame(s).
- Enforce the CONTINUATION-frame interleaving rules and the security bounds that make this safe
  (CVE-2024-27316).

## Non-Goals

- PUSH_PROMISE (server push): disabled via `SETTINGS_ENABLE_PUSH = 0`; its CONTINUATION interaction
  is out of scope.
- Trailers (a second HEADERS block after DATA). Catfish doesn't process request trailers today;
  this spec does not add them.
- Raising the max frame size or max header list size. The existing
  `HpackDecoder.DEFAULT_MAX_HEADER_LIST_SIZE` (32 KB decoded) ceiling stays.

## Background / Context

Catfish does not support CONTINUATION frames in either direction:

- **Inbound:** `Http2ServerStage.handleHeaders` throws a connection error if a HEADERS frame lacks
  `END_HEADERS`, and `processFrame` throws on any `CONTINUATION` frame ("not supported"). Per RFC
  9113 §6.2, when an encoded header block does not fit in a single HEADERS frame the sender **must**
  split it: one HEADERS frame without `END_HEADERS`, followed by one or more CONTINUATION frames,
  the last carrying `END_HEADERS`. A header block only has to exceed the max frame size (default
  16 KB; `Http2FrameReader` hard-caps payloads at 16384 bytes) to require this. Real requests hit
  that: large cookies, long URLs/query strings, many/long headers, or a cold HPACK dynamic table.
  Against such a client Catfish kills the connection instead of serving the request.
- **Outbound:** `Http2FrameWriter.writeHeaders` always sets `END_HEADERS` and writes the entire
  pre-encoded HPACK block as a single HEADERS frame. If a *response* header block ever exceeds the
  peer's `SETTINGS_MAX_FRAME_SIZE`, Catfish emits an illegal oversized frame. Latent today (Catfish
  responses are small), but the same missing capability.

Relevant code: `Http2ServerStage` (`processFrame`, `handleHeaders`), `Http2FrameReader`,
`Http2FrameWriter`, `Http2Stream.writeResponseFrames`, `HpackDecoder`. Related mitigations already
present: Rapid Reset (CVE-2023-44487) and HPACK amplification (the `maxHeaderListSize` ceiling). No
`ARCHITECTURE.md` yet.

## Design

### Inbound reassembly (the core change)

The frame reader stays frame-at-a-time; reassembly lives in `Http2ServerStage`, which already drives
`processFrame()` per complete frame. Introduce an explicit **header-block assembly** state:

- Fields: a `@Nullable` growable accumulation buffer, the `headerStreamId` the block belongs to, the
  saved HEADERS flags (`END_STREAM`; padding/priority already stripped), and a running byte count.
- `handleHeaders`: strip padding/priority as today to isolate the HPACK fragment. If `END_HEADERS`
  is set, decode immediately (unchanged fast path). If not, **stash** the fragment bytes + stream id
  / flags and enter "expecting CONTINUATION" mode — do **not** decode yet.
- `processFrame`: while in assembly mode, the **only** legal next frame is a `CONTINUATION` on the
  *same* stream id. This is not Catfish being conservative — RFC 9113 §6.2 mandates it: "A HEADERS
  frame without the END_HEADERS flag set MUST be followed by a CONTINUATION frame for the same
  stream. A receiver MUST treat the receipt of any other type of frame or a frame on a different
  stream as a connection error of type PROTOCOL_ERROR." So any other frame type, a CONTINUATION on a
  different stream, or a CONTINUATION when not in assembly mode is a `PROTOCOL_ERROR` connection
  error.

  **Why this does not reintroduce head-of-line blocking:** HPACK's dynamic table is a single
  connection-global, order-dependent coding stream shared by *all* streams (RFC 7541). Header
  fragments from different streams therefore *cannot* be interleaved without corrupting every
  subsequent decode on the connection — the spec forbids interleaving precisely because it is
  physically impossible for HPACK, not to serialise streams. Head-of-line blocking is a concern for
  *large payloads* (DATA frames, which remain fully interleavable across streams); header blocks are
  small and hard-bounded (see Security Considerations), so requiring one block to be transmitted
  contiguously costs nothing in multiplexing.
- `handleContinuation` (new): append the fragment; when `END_HEADERS` arrives, concatenate all
  fragments, HPACK-decode the whole block **once**, and run the existing pseudo-header
  extraction / request-building / routing path (factor that tail of `handleHeaders` into a shared
  `dispatchDecodedHeaderBlock(streamId, endStream, block)`).

HPACK is stateful across the connection, so decoding must happen only over the fully reassembled
block, in order — never per fragment.

### Outbound splitting

In `Http2Stream.writeResponseFrames` / `Http2FrameWriter`, when the encoded response header block is
larger than the effective max frame size, emit a HEADERS frame (no `END_HEADERS`) with the first
`maxFrameSize` bytes, then CONTINUATION frames for the remainder, the last with `END_HEADERS`. Add
`Http2FrameWriter.writeContinuation(...)` and teach the writer to chunk, respecting the existing
buffer-space/`BLOCKED` backpressure protocol so a multi-frame header block can be written across
several `write()` calls without corrupting framing.

No public API changes — everything is within package `de.ofahrt.catfish.http2`. `HttpHandler` still
sees a fully assembled `HttpRequest`.

## Security Considerations

This is the load-bearing section — CONTINUATION is a known DoS vector.

- **CONTINUATION flood (CVE-2024-27316):** an attacker sends a HEADERS frame without `END_HEADERS`
  followed by an unbounded stream of CONTINUATION frames (or many tiny fragments), forcing the
  server to buffer/decode without limit. Mitigations, all required and enforced *incrementally* as
  fragments arrive: (1) cap total accumulated compressed header-block bytes at 64 KB → GOAWAY;
  (2) cap the number of CONTINUATION frames per block → GOAWAY; (3) the existing 32 KB decoded
  `maxHeaderListSize` ceiling still applies after reassembly.
- **Interleaving / state confusion (smuggling surface):** while assembling a header block, no other
  frame may arrive (not DATA, not another HEADERS, not SETTINGS, not a CONTINUATION for a different
  stream) — RFC 9113 §6.2 requires treating any such frame as a `PROTOCOL_ERROR`. This is mandated,
  not a choice, and does not cause head-of-line blocking (HPACK's dynamic table is a single
  connection-global ordered stream, so header fragments are inherently un-interleavable; only
  large DATA payloads, which stay interleavable, matter for HoL). A lax reader here is a framing
  vulnerability.
- **HPACK dynamic-table integrity:** decode only over the fully reassembled block; a partial decode
  would desync the per-connection dynamic table for every later request. Stream-id validation
  (odd, monotonic) happens once when the HEADERS frame opens the block.
- **GOAWAY during assembly:** an in-progress block is still consumed to keep dynamic-table state
  consistent, but the resulting stream is not opened (mirrors existing post-GOAWAY behaviour).
- **NIO thread:** reassembly and decode are CPU-only over bounded buffers; no blocking. Outbound
  multi-frame writes use the existing `BLOCKED` backpressure protocol.

## Decisions

- **Decision:** Cap total accumulated (compressed) header-block bytes per request at 2×
  `DEFAULT_MAX_HEADER_LIST_SIZE` (= 64 KB), enforced incrementally; exceeding it is a connection
  error (GOAWAY). — *Rationale:* the decoded ceiling is 32 KB; allowing ~2× compressed headroom
  admits legitimate blocks (HPACK usually shrinks, but padding/never-indexed fields can grow) while
  bounding the CONTINUATION-flood buffer (CVE-2024-27316).
- **Decision:** Cap the number of CONTINUATION frames per header block at a small constant. —
  *Rationale:* bounds tiny-fragment amplification even under the byte cap (many 1-byte fragments).
- **Decision:** Strict interleaving — while assembling a block, any non-CONTINUATION frame, or a
  CONTINUATION for a different stream, is a `PROTOCOL_ERROR`. — *Rationale:* RFC 9113 §6.2 *mandates*
  this, and HPACK's connection-global ordered dynamic table makes cross-stream header interleaving
  impossible anyway, so it does not reintroduce head-of-line blocking (only large DATA payloads,
  which remain interleavable, affect HoL). A lax reader here is a framing/smuggling vulnerability.
- **Decision:** The security ceilings are internal constants, not configurable. — *Rationale:*
  generous enough not to affect legitimate traffic; a follow-up can expose them if a real deployment
  needs tuning, without changing the wire behaviour.
- **Decision:** Decode over the fully reassembled block only, never per fragment. — *Rationale:*
  HPACK dynamic-table state is per-connection; a partial decode desyncs the table for every later
  request on the connection.

## Open Questions

None.

## Acceptance Criteria

- [ ] A request whose HPACK header block is split across a HEADERS frame (no `END_HEADERS`) + one
      CONTINUATION frame (with `END_HEADERS`) is reassembled, decoded, and served correctly.
- [ ] A block split across a HEADERS frame + **multiple** CONTINUATION frames is served correctly.
- [ ] HPACK dynamic-table state stays correct across a reassembled block: a subsequent request on
      the same connection that references dynamic-table entries established by the reassembled block
      decodes correctly.
- [ ] A CONTINUATION frame received when no header block is in progress is a `PROTOCOL_ERROR`
      connection error.
- [ ] Any non-CONTINUATION frame, or a CONTINUATION for a different stream, received while a header
      block is in progress is a `PROTOCOL_ERROR` connection error.
- [ ] Accumulated compressed header-block bytes exceeding the 64 KB ceiling terminates the
      connection (GOAWAY) rather than buffering unboundedly.
- [ ] Exceeding the maximum number of CONTINUATION frames for one block terminates the connection.
- [ ] Outbound: a response whose encoded header block exceeds the peer's max frame size is written
      as HEADERS (no `END_HEADERS`) + CONTINUATION frame(s), the last with `END_HEADERS`, and a
      conformant client reassembles it.
- [ ] Existing single-frame HEADERS request/response behaviour is unchanged (all current h2 tests
      pass unmodified).
- [ ] Tests: `Http2ServerStageTest` sequences for reassembly + all connection-error paths;
      `Http2FrameWriterTest`/`Http2StreamTest` for outbound splitting at a forced small frame size;
      `Http2IntegrationTest` end-to-end with a header block large enough to force CONTINUATION.
- [ ] `bazel test //...` green and `bazel run //:format.check` passes.

## Implementation Plan

- [ ] PR 1: Inbound reassembly state machine in `Http2ServerStage` — stash non-`END_HEADERS`
      HEADERS, accept same-stream CONTINUATION via `handleContinuation`, factor the shared
      `dispatchDecodedHeaderBlock`, and enforce strict interleaving as `PROTOCOL_ERROR`. Unit tests
      for the happy paths and every error path.
- [ ] PR 2: Security bounds — incremental 64 KB compressed-byte cap and CONTINUATION-frame-count cap
      → GOAWAY. Targeted tests using many small fragments.
- [ ] PR 3: HPACK-continuity test (two requests where request 2's compression depends on request 1's
      reassembled block).
- [ ] PR 4: Outbound splitting — `Http2FrameWriter.writeContinuation` + chunking in
      `Http2Stream.writeResponseFrames` under the `BLOCKED` backpressure protocol; frame-level and
      integration tests.

## Notes

- Backwards compatible and strictly more conformant: requests Catfish previously killed now succeed,
  and no currently-accepted request changes behaviour.
- Follow-up: expose the security ceilings as configuration if a real deployment needs tuning (see
  Decisions).

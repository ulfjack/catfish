---
id: 0008
title: Disk-spooled request bodies (stream large uploads to a temp file)
status: ready
owner: Ulf Adams
architecture_refs:
  - Model (HttpRequest.Body)
  - Server (LocalHttpRequestStage, HttpServerStage, Http2Stream, Http2ServerStage)
  - Upload (UploadPolicy)
  - Network (Stage / ConnectionControl flow control)
---

# 0008 — Disk-spooled request bodies (stream large uploads to a temp file)

## Summary

Support large (>1 GiB) request-body uploads without holding the whole body in the JVM heap: as the
body streams in, write it incrementally to a temporary file on disk, with backpressure so the network
never outruns the disk. Handlers read the body back through the existing `HttpRequest.Body.openStream()`
on the application thread pool, where blocking disk reads are fine.

## Goals

- Accept request bodies far larger than heap (target: >1 GiB) with **bounded memory** — at most a
  small, fixed amount of in-flight buffering per upload, independent of body size.
- Bodies below a configurable threshold stay entirely in memory (unchanged from today).
- Spilling is **incremental**: bytes are written to disk as they arrive, never buffered whole first.
- **Backpressure**: when the disk can't keep up, the sender is throttled (no unbounded in-heap
  write queue), wired into Catfish's existing flow control.
- The **read** side is transparent — handlers read via `Body.openStream()` regardless of backing.
- Temp files are always cleaned up (normal completion, handler error, connection/stream abort).
- Off by default: with no threshold configured, behaviour is byte-for-byte unchanged.

## Non-Goals

- **Transparent writes.** The read side is transparent; the receive/spill side is explicitly not —
  it is threading- and backpressure-sensitive and lives in the network stages, not behind `Body`.
- **Per-part multipart spooling.** Streaming each `multipart/form-data` part to its own file and a
  `FormEntry.moveTo/copyTo` API is a follow-up built on this.
- **Bodies >2 GiB.** `Content-Length` and `SimpleUploadPolicy` are `int`-bounded (~2.14 GB), which
  covers the >1 GiB target. Widening to `long` is a separate follow-up.
- **Outbound / client bodies.** `client/HttpRequestGeneratorBuffered` still assumes `InMemoryBody`.
- **Incremental handoff to handlers.** Handlers still see the request only after the body is complete.

## Background / Context

- The body is buffered whole in heap today: `LocalHttpRequestStage.bodyBuffer` (`ByteArrayOutputStream`,
  `LocalHttpRequestStage.java:65`), fed by `onBodyData` on the **selector thread** (`:147`); and
  `Http2Stream.bodyBuffer` (`Http2Stream.java:56`), fed by `appendBodyData` — also "NIO thread only"
  (`:187`). Both wrap the result in `InMemoryBody`.
- `HttpRequest.Body` is now a streaming interface (`openStream()` + `length()`); `InMemoryBody` is the
  only implementation. Consumers already read via `openStream()`.
- Reads run on the **application thread pool** (handlers, and `HttpResponseGenerator`), where blocking
  is acceptable. Receives run on the **selector thread**, which must never block (AGENTS.md).
- Backpressure already exists: HTTP/1.1 `read()` returns `ConnectionControl.PAUSE` to stop reading and
  resumes via `encourageReads` (`ProxyRequestStage.java:114` does exactly this async pause/resume);
  HTTP/2 DATA is gated by the per-stream flow-control window (`Http2Stream.appendBodyData` is
  "bounded by flow control", `:200-207`).
- `UploadPolicy.maxDecodedBytes(request)` is the per-request size ceiling (413 on exceed).

## Design

### Backing type

`FileBackedBody implements HttpRequest.Body`: holds the temp-file `Path` and the final length;
`openStream()` returns a buffered `Files.newInputStream(path)`, `length()` the byte count. No lifecycle
method on the interface — cleanup is the server's job (below).

### Spill sink

A `BodySink` abstraction replaces both `bodyBuffer` fields: accumulate in memory up to the threshold,
then create a temp file, flush buffered bytes, and stream subsequent bytes to it. `finish()` yields
`InMemoryBody` (stayed ≤ threshold) or `FileBackedBody`. One implementation, both protocols.

### Receive → decode → write pipeline (the core)

Bytes arrive on the selector thread and must not be written to disk there. The selector hands raw
chunks to a **disk I/O worker** (a shared, bounded thread pool); the worker de-chunks/decompresses
and writes **decoded** bytes to the file, enforcing `maxDecodedBytes` as it goes. Decoding decoded-side
(not raw) keeps `openStream()` yielding exactly what handlers expect. De-chunking uses the existing
incremental `ChunkedBodyState.Sink`; gzip uses a streaming inflater bounded by the ceiling.

**Backpressure** is mandatory (bounded memory + a producer that can outrun the disk): each upload has a
**bounded** in-flight chunk queue. When it fills:

- **HTTP/1.1:** the body-read path returns `ConnectionControl.PAUSE`; the worker calls
  `encourageReads` (via `parent.queue`) when the queue drains — mirroring `ProxyRequestStage`.
- **HTTP/2:** withhold `WINDOW_UPDATE` for the stream until the queue drains; the client's flow-control
  window stalls the sender. This replaces today's immediate window replenishment for spilled bodies.

When the JDK gains io_uring-backed `FileChannel` on virtual threads (non-carrier-blocking), the worker
pool can become virtual threads with no contract change.

### Cleanup (server-owned, idempotent)

There is no unified per-request teardown hook today, and normal-completion and abort paths are
structurally separate. So the stage/stream that created the spill file **retains the `Path` and deletes
it idempotently** (`Files.deleteIfExists`) from every teardown site it already has:

- **HTTP/1.1:** normal completion at `HttpServerStage.write()` STOP, and abort at
  `HttpServerStage.close()` / `inputClosed()`. The stage must retain the spilled `Path` (it does not
  retain the request after `queueRequest` today — this is the one new field).
- **HTTP/2:** `Http2Stream` holds the `Path`; `Http2ServerStage.discardStream` (RST/GOAWAY/close) and
  normal completion both release it.

Idempotent delete makes "release from multiple sites" and "file not yet created" both harmless.

### Configuration

- **Spill threshold** (per-request): a `UploadPolicy` default method, `Long.MAX_VALUE` = never spill.
- **Temp directory** and **disk-worker pool**: one server-level setting (default `java.io.tmpdir`),
  not per-request.

## Security Considerations

- **Disk exhaustion.** Spilling trades heap exhaustion for disk exhaustion. `maxDecodedBytes` still
  caps each body; the per-stream threshold applies per HTTP/2 stream, so N concurrent streams can use
  up to N × ceiling of disk — an optional global on-disk budget is a deferred follow-up. Leak-free
  cleanup is the primary defence and is acceptance-critical.
- **Temp-file disclosure.** Spill files hold raw request data; create them owner-only (`rw-------`,
  e.g. `Files.createTempFile` + POSIX perms, or an unnamed/`O_TMPFILE` file) in the configured dir —
  never a predictable name in a shared world-writable `/tmp` (symlink/TOCTOU).
- **Cleanup on every abort path.** A temp file outliving its request leaks data and fills disk; a slow
  or reset upload must not accumulate files. Covered by idempotent release from all teardown sites.
- **Selector-thread stalls.** All blocking disk I/O runs on the worker pool; the selector only enqueues
  and adjusts flow control. A slow/full disk applies backpressure, never blocks a selector thread.
- **Decompression bombs.** The gzip ceiling is enforced during streaming (decoded-side, bounded by
  `maxDecodedBytes`), so a small compressed body cannot spill an unbounded file.
- **Framing / smuggling.** Unchanged; de-chunking and length enforcement move into the streaming
  worker but keep the same strict semantics.

## Decisions

- **Decision:** Stream incrementally to disk with backpressure; do **not** buffer the whole body in
  heap then spill. — *Rationale:* the >1 GiB goal requires bounded memory; buffer-then-spill bounds
  only the resting place, not peak heap.
- **Decision:** Spill **decoded** bytes (post de-chunk/gunzip). — *Rationale:* `openStream()` must yield
  what the handler expects and what the ceiling measures.
- **Decision:** Do blocking disk I/O on a dedicated worker pool, not the selector; use "async"
  `AsynchronousFileChannel` only as an equivalent (it's thread-pool-backed on Linux today anyway). —
  *Rationale:* selector must not block; the worker is the place blocking is allowed.
- **Decision:** Backpressure via existing flow control — HTTP/1.1 `PAUSE`/`encourageReads`, HTTP/2
  withheld `WINDOW_UPDATE`. — *Rationale:* reuse the proven machinery; a bounded queue + throttled
  producer is the only way to keep memory bounded.
- **Decision:** Read side transparent via `Body.openStream()` on the application pool; write side is
  explicit in the stages. — *Rationale:* blocking is fine on the app pool but forbidden on the selector,
  so hiding the write behind the interface would be a footgun.
- **Decision:** Server owns temp-file cleanup, released idempotently from all teardown sites; `Body`
  gains no lifecycle method. — *Rationale:* a closeable `HttpRequest`/`Body` would push server
  resource management onto every handler; there is no single teardown hook, so idempotent
  release-from-all-sites is the robust option.
- **Decision:** Threshold per-request on `UploadPolicy`; temp dir + worker pool server-level. —
  *Rationale:* matches the scope of each knob.
- **Decision:** Off by default. — *Rationale:* zero behaviour change for existing embedders.

## Open Questions

None — resolved into Decisions above. (Deferred to follow-ups, not blocking: global on-disk budget,
multipart per-part spooling, >2 GiB bodies, outbound file-backed bodies.)

## Acceptance Criteria

- [ ] A >1 GiB upload over HTTP/1.1 **and** HTTP/2 is accepted with bounded process memory (peak heap
      stays within a small multiple of the chunk-queue bound, not the body size), spilled to a temp
      file, and read back byte-exact by a handler via `openStream()`. (Integration tests, both
      protocols, with a memory assertion.)
- [ ] A body ≤ threshold stays in memory (`InMemoryBody`); the boundary (== threshold) is covered.
- [ ] Backpressure works: with a deliberately slow disk sink, the server pauses HTTP/1.1 reads /
      stalls the HTTP/2 window instead of growing heap; it resumes and completes correctly.
- [ ] The temp file is deleted after normal completion, handler exception, early 413/400, mid-body
      HTTP/1.1 abort, and HTTP/2 `RST_STREAM`; the spool dir ends empty. (Integration tests.)
- [ ] Spill files are owner-only-readable in the configured dir. (POSIX-perms test.)
- [ ] `maxDecodedBytes` still yields 413 for an over-ceiling body while spilling; a gzip bomb does not
      spill an unbounded file.
- [ ] With no threshold configured, behaviour is unchanged and bodies stay in memory. (Regression.)
- [ ] No blocking file I/O runs on a selector thread. (Design/threading review or instrumentation.)
- [ ] Tests join their package suites; `bazel test //...` green; `bazel run //:format.check` passes;
      NullAway clean.

## Implementation Plan

- [ ] PR 1: `FileBackedBody` + the `BodySink` (memory→file overflow, single-threaded, no stage wiring)
      with unit tests including the threshold boundary and file permissions.
- [ ] PR 2: disk-worker pool + bounded per-upload queue + incremental decode (de-chunk/gzip) with
      ceiling enforcement, as a standalone streaming component with tests (including a slow-sink
      backpressure test at the component level).
- [ ] PR 3: server-owned temp-file cleanup — retain the `Path` in the HTTP/1.1 stage and `Http2Stream`,
      release idempotently from all teardown sites; abort-path tests (abort-before-dispatch,
      abort-mid-body, RST, keep-alive reuse) using a small forced-spill threshold.
- [ ] PR 4: wire the pipeline into `LocalHttpRequestStage` / `HttpServerStage` (HTTP/1.1) with
      `PAUSE`/`encourageReads` backpressure; end-to-end large-upload test.
- [ ] PR 5: wire into `Http2Stream` / `Http2ServerStage` (HTTP/2) with `WINDOW_UPDATE`-gated
      backpressure and per-stream accounting; end-to-end large-upload test; README/docs.

## Notes

- **Follow-ups (deferred):** global on-disk spool budget; multipart per-part spooling with
  `FormEntry.moveTo/copyTo`; >2 GiB via `long` `Content-Length`/policy; outbound file-backed bodies;
  virtual-thread disk workers once io_uring-backed `FileChannel` lands.
- Risk is concentrated in PR 2 (streaming + backpressure) and PR 3 (leak-free cleanup); PR 1 is a
  low-risk building block.

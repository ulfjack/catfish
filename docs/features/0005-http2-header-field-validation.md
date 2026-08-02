---
id: 0005
title: HTTP/2 request header field validation
status: ready
owner: Ulf Adams
architecture_refs:
  - HTTP/2 (Http2ServerStage, HpackDecoder)
---

# 0005 — HTTP/2 request header field validation

## Summary

Validate decoded HTTP/2 request header fields — field-name and field-value character rules,
pseudo-header ordering/uniqueness, and the connection-specific fields h2 forbids — and reject a
malformed request with a stream error (`RST_STREAM` / `PROTOCOL_ERROR`) instead of building an
`HttpRequest` from it. Today `Http2ServerStage` adds every decoded header to the request as-is, so
uppercase names, embedded colons, and `CR`/`LF`/`NUL` in values pass straight through to handlers and
to the reverse-proxy / FastCGI forwarders. Closes conformance rules #37–#41 and the §8.2.2 / §8.3
malformed-request rules.

## Goals

- Reject a request whose HPACK-decoded header block violates RFC 9113 field validity, as a **stream**
  error (`RST_STREAM(PROTOCOL_ERROR)`), leaving the connection and all other streams intact.
- Enforce, on each decoded field of a request header block:
  - **#38** field name contains only lowercase RFC 7230 `tchar` — no uppercase, SP, or non-visible/control ASCII;
  - **#39** field name contains no `:` except a leading, recognised pseudo-header;
  - **#40** field value contains no `NUL`, `LF`, or `CR`;
  - **#41** field value has no leading or trailing SP/HTAB.
- Enforce the §8.3 pseudo-header rules (ordering before regular fields, at-most-once, no unknown
  pseudo-header) and the §8.2.2 connection-specific field rules (`Connection`, `Keep-Alive`,
  `Proxy-Connection`, `Transfer-Encoding`, `Upgrade` forbidden; `TE` only if its value is `trailers`).
- Reject a handler-returned 1xx/`101` `:status` over HTTP/2 (**#37**).
- Keep the HPACK dynamic table correct after a rejected stream: a later stream on the same connection
  that depends on the rejected block's dynamic-table updates still decodes correctly.
- Update the conformance matrix Coverage column (#37–#41 and the §8.2.2/§8.3 rules → tests) as each PR lands.

## Non-Goals

- **Request trailers.** Catfish does not process request trailers (consistent with spec 0003); their
  validation is out of scope.
- **HPACK-layer errors.** A decode failure is already a `COMPRESSION_ERROR` connection error and is
  unchanged.
- **Response-side field syntax** beyond #37 (that is already covered by `HttpResponseValidator`).
- Raising or changing any size ceiling (the 32 KB `maxHeaderListSize` from 0003 is untouched).

## Background / Context

`Http2ServerStage` decodes the reassembled HPACK block once (`hpackDecoder.decode(...)`, currently
~line 425) and then loops over the decoded `Header`s to build the request (~lines 437–449):

- pseudo-headers other than `:method` / `:path` / `:authority` / `:scheme` are **silently dropped**
  (`default -> {}`, ~line 444) — including unknown pseudo-headers and any pseudo-header that appears
  *after* a regular field (RFC 9113 §8.3 forbids both);
- every regular header is added verbatim (`builder.addHeader(name, value)`, ~line 447) with **no**
  name or value validation.

By contrast the HTTP/1.1 path validates during parsing: `IncrementalHttpRequestParser` rejects illegal
field-name/value characters (`isTokenCharacter`, "Illegal character in header field name/value"). h2
has no equivalent. RFC 9113 §8.1.1 classifies these as **malformed requests** and requires treating
them as a **stream error of type `PROTOCOL_ERROR`** — i.e. `RST_STREAM`, not a connection kill.

Error conventions already in the file: connection errors `throw new IOException("h2 PROTOCOL_ERROR: …")`;
stream errors `queueRstStream(streamId, ErrorCode.X)`. The Rapid Reset (CVE-2023-44487) defense frees a
stream slot only when the handler completes.

Relevant code: `Http2ServerStage` (the decoded-header loop that builds the request; `queueRstStream`;
the response-encoding path for #37), `IncrementalHttpRequestParser.isTokenCharacter` (reusable `tchar`
check), `HpackDecoder`, `HttpResponseValidator` (existing response validator, for reference on #37).

## Design

Validation runs **after** the full HPACK decode (so the dynamic table is always advanced over the
whole block, exactly as today) and **before** the stream is opened or routed — i.e. inside/around the
decoded-header loop, before `streams.put(...)` and before `connectHandler.applyLocal(...)`.

Add a private `validateRequestHeaderBlock(streamId, headers)` that walks the decoded fields and, on the
first violation, calls `queueRstStream(streamId, ErrorCode.PROTOCOL_ERROR)` and returns a signal that
the caller must **not** open the stream or dispatch. Rules:

- **Field name (#38, #39):** non-empty; every char is `tchar` (reuse
  `IncrementalHttpRequestParser.isTokenCharacter`) **and** not uppercase `A–Z`; no interior `:`.
  A name starting with `:` is a pseudo-header and validated by the pseudo-header rules instead.
- **Field value (#40, #41):** contains no `0x00`, `0x0A`, `0x0D`; first and last chars are not SP/HTAB.
- **Pseudo-headers (§8.3):** every pseudo-header precedes all regular fields; each of
  `:method`/`:path`/`:authority`/`:scheme` appears at most once; any unrecognised pseudo-header
  (currently dropped) is malformed.
- **Connection-specific fields (§8.2.2):** `connection`, `keep-alive`, `proxy-connection`,
  `transfer-encoding`, `upgrade` are malformed; `te` is malformed unless its value is exactly `trailers`.

Because a malformed request is a *stream* error, `processFrame` keeps running and the connection
survives. The rejected stream is never handed to a handler; it is `RST_STREAM`-ed and dropped.

**#37 (response side):** on the response-encoding path, a handler-supplied status of `101` or any 1xx
over HTTP/2 is invalid (RFC 9113 §8.1). Treat it like the existing internal-error path (send the stream
an error response / `RST_STREAM`) rather than encoding an illegal `:status`.

No public API changes — everything is within `de.ofahrt.catfish.http2`; `HttpHandler` still only ever
sees a fully-valid `HttpRequest`.

## Security Considerations

- **h2→h1 downgrade request smuggling / header injection (the load-bearing risk).** Catfish forwards
  request headers to upstreams over HTTP/1.1 via the reverse proxy (`OriginForwarder`) and FastCGI
  (`FcgiHandler`). An h2 field *value* containing `CR`/`LF`, or a field *name* carrying `:` /
  whitespace / control chars, is harmless in the binary h2 framing but becomes header-splitting or
  request smuggling the moment it is re-serialised into a text HTTP/1.1 request (or written to a log).
  The §8.2.2 fields (`Transfer-Encoding`, `Connection`, `TE`) are the classic h2→h1 smuggling vector
  specifically. Rejecting all of these at the h2 boundary — where the HTTP/1.1 parser already rejects
  their h1 equivalents — removes the injection primitive. This is the primary justification for the spec.
- **Malformed request is a stream error, not a connection error.** Per RFC 9113 §8.1.1 we `RST_STREAM`
  the offending stream and keep the connection; a single bad request must not tear down concurrent
  well-formed streams. Rejecting connection-wide would itself be a DoS lever.
- **HPACK dynamic-table integrity.** Validation happens after decoding the full block, so the
  per-connection dynamic table is advanced identically whether or not the request is rejected; a
  rejected stream cannot desync decoding for later streams.
- **Rapid-Reset interaction.** A stream rejected at header time never reaches a handler. The design
  must ensure such streams do not leak a slot and are accounted against the existing reset budget the
  same way a client `RST_STREAM` would be (a flood of malformed-header streams must not be a cheaper
  Rapid-Reset variant). Covered by a Decision and a targeted test.
- **NIO thread.** Validation is CPU-only over the already-decoded, size-bounded (≤ 32 KB) header list;
  no allocation beyond a possible error string, no blocking.

## Decisions

- **Decision:** A malformed request header block is a **stream** error `RST_STREAM(PROTOCOL_ERROR)`, not
  a connection error. — *Rationale:* RFC 9113 §8.1.1 mandates it; connection-wide rejection would let
  one request kill concurrent streams.
- **Decision:** Validate after the full HPACK decode, before opening/routing the stream. — *Rationale:*
  keeps the dynamic table advanced over the whole block regardless of outcome; rejects before any
  handler or forwarder sees the request.
- **Decision:** Field name must be lowercase `tchar` with no interior `:`; reuse
  `IncrementalHttpRequestParser.isTokenCharacter` plus an uppercase check. — *Rationale:* RFC 9113
  §8.2.1; reuse keeps the h1 and h2 token definitions from drifting.
- **Decision:** A rejected-at-header stream is counted against the Rapid-Reset budget and frees no slot
  improperly. — *Rationale:* prevents a malformed-header flood from being a cheaper CVE-2023-44487.
- **Decision (resolves scope of §8.2.2):** The §8.2.2 connection-specific fields (`Connection`,
  `Keep-Alive`, `Proxy-Connection`, `Transfer-Encoding`, `Upgrade`, and `TE` ≠ `trailers`) are **in
  scope** for this spec, as PR 3. — *Rationale:* they share the reject-as-`RST_STREAM` mechanism and are
  the most smuggling-relevant fields, central to the stated security goal; deferring them would leave
  the primary risk open.
- **Decision (resolves scope of #37):** Response-side #37 (reject 1xx/`101` `:status` over h2) is **in
  scope** as the final PR 4, not a separate spec. — *Rationale:* small, belongs to the same #37–#41
  cluster, but on a different code path, so it lands last and independently of the request-side PRs.
- **Decision (resolves unify-with-h1):** Keep an h2-local `validateRequestHeaderBlock`, reusing only
  `isTokenCharacter`; do **not** extract a shared validator yet. — *Rationale:* avoids a premature
  abstraction across two differently-shaped call sites (h1 validates incrementally during parse, h2
  over a decoded list); a follow-up may extract a shared `HttpFieldSyntax` once both are proven.

## Open Questions

None.

## Acceptance Criteria

- [ ] An h2 request with an uppercase field name (e.g. `Foo: bar`) is `RST_STREAM(PROTOCOL_ERROR)`; the
      connection stays open and a subsequent valid stream on it is served. (#38)
- [ ] An h2 request with a field name containing an interior `:` is rejected as above. (#39)
- [ ] An h2 request with `CR`, `LF`, or `NUL` in a field value is rejected as above. (#40)
- [ ] An h2 request with a field value having leading or trailing SP/HTAB is rejected as above. (#41)
- [ ] An h2 request with a pseudo-header after a regular field, a duplicate `:method`/`:path`/
      `:authority`/`:scheme`, or an unknown pseudo-header is rejected. (§8.3)
- [ ] An h2 request carrying `Connection`/`Transfer-Encoding`/`Upgrade`/`Keep-Alive`/`Proxy-Connection`,
      or `TE` with a value other than `trailers`, is rejected. (§8.2.2)
- [ ] A handler returning a `101` or 1xx status over h2 does not emit an illegal `:status`; the stream
      is failed instead. (#37)
- [ ] HPACK continuity: after a rejected stream whose block updated the dynamic table, a later stream
      that references those entries still decodes and is served correctly.
- [ ] A flood of malformed-header streams is bounded by the existing Rapid-Reset defense (no slot leak).
- [ ] All existing h2 tests pass unmodified; a valid request with lowercase names and clean values is
      unaffected.
- [ ] Conformance matrix Coverage column updated for #37–#41 and the §8.2.2/§8.3 rules.
- [ ] `bazel test //...` green and `bazel run //:format.check` passes.

## Implementation Plan

- [ ] PR 1: `validateRequestHeaderBlock` for field-name (#38, #39) and field-value (#40, #41) rules →
      `RST_STREAM(PROTOCOL_ERROR)`; `Http2ServerStageTest` for each rule + connection-survives +
      HPACK-continuity; `Http2IntegrationTest` end-to-end. Tag tests `Conformance test #38…#41`.
- [ ] PR 2: Pseudo-header ordering / uniqueness / unknown-pseudo rejection (§8.3), with tests.
- [ ] PR 3: §8.2.2 connection-specific field rejection incl. `TE` ≠ `trailers`, with tests.
- [ ] PR 4: Response-side #37 — disallow 1xx/`101` `:status` over h2, with tests.

## Notes

- Strictly more conformant and backwards compatible for well-formed clients: every currently-accepted
  *valid* request is unchanged; only requests the HTTP/1.1 path would already reject start being
  rejected on h2 too.
- Related: spec 0003 (CONTINUATION reassembly) established the "decode the whole block once, then act"
  shape this builds on. Conformance rules tracked in `third_party/http-conformance-tests.md`.

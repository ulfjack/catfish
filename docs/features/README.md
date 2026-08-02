# Feature specs

This directory holds numbered feature specs that are **`ready`** or beyond — reviewed, with every
open question resolved into a Decision. See
[../development/spec-driven-development.md](../development/spec-driven-development.md) for the
process and [TEMPLATE.md](TEMPLATE.md) for the shape of a spec. Pre-ready thinking with open
questions lives in [../proposals/](../proposals/) instead.

Numbering is sequential and zero-padded (`0001`, `0002`, …). Pick the next free number. Each spec's
`status` frontmatter (`ready` → `in-progress` → `implemented`, or `superseded`) is mirrored in the
Status column below.

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-alpn-http1-http2-selection.md) | Unify TLS endpoints with ALPN HTTP/1.1 + HTTP/2 selection | implemented |
| [0002](0002-chunked-gzip-uploads.md) | Uploads without Content-Length; chunked + gzipped request bodies | ready |
| [0003](0003-http2-continuation-frames.md) | HTTP/2 CONTINUATION frame support | ready |
| [0004](0004-reverse-proxy-unix-socket.md) | Reverse-proxy an upstream over a unix domain socket | implemented |
| [0005](0005-http2-header-field-validation.md) | HTTP/2 request header field validation | implemented |
| [0006](0006-http2-response-compression.md) | HTTP/2 response compression parity via a shared response-writer decorator | implemented |

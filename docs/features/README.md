# Feature specs

This directory holds numbered feature specs. Each non-trivial change to Catfish starts life here as
a spec, reviewed before any code is written. See
[../development/spec-driven-development.md](../development/spec-driven-development.md) for the
process and [TEMPLATE.md](TEMPLATE.md) for the shape of a spec.

Numbering is sequential and zero-padded (`0001`, `0002`, …). Pick the next free number.

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-alpn-http1-http2-selection.md) | ALPN HTTP/1.1 + HTTP/2 protocol selection | Draft |
| [0002](0002-chunked-gzip-uploads.md) | Uploads without Content-Length; chunked + gzipped request bodies | Draft |
| [0003](0003-http2-continuation-frames.md) | HTTP/2 CONTINUATION frame support | Draft |

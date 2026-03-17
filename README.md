# Catfish Java HTTP Library

Catfish is a Java library for embedding an HTTP/1.1 server into any JVM application. It provides
low-level control over the HTTP protocol without imposing a framework: no annotation scanning, no
dependency injection, no servlet container (though an optional servlet bridge is available).

Key capabilities: non-blocking I/O, TLS with SNI-based virtual hosting, streaming responses, and
keep-alive / compression policies per virtual host.

## Requirements

- Java 21+
- [Bazel](https://bazel.build/) with bzlmod for building

## Quick start

### Implement a handler

`HttpHandler` is a single-method interface:

```java
// Buffered response — small, complete responses assembled in memory
HttpHandler handler = (connection, request, writer) ->
    writer.commitBuffered(StandardResponses.OK);
```

For large or dynamic output use `commitStreamed`, which sends the body with chunked
transfer encoding:

```java
HttpHandler handler = (connection, request, writer) -> {
    HttpResponse response = StandardResponses.OK.withHeaderOverrides(
        HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, MimeType.TEXT_HTML.toString()));
    try (Writer out = new OutputStreamWriter(
            writer.commitStreamed(response), StandardCharsets.UTF_8)) {
        out.append("<!DOCTYPE html><html>...");
    }
};
```

### Start the server

```java
CatfishHttpServer server = new CatfishHttpServer(eventListener);

server.addHttpHost(
    "localhost",
    UploadPolicy.DENY,
    ResponsePolicy.KEEP_ALIVE,
    handler,
    /* sslContext= */ null);

server.listenHttp(8080);
server.listenHttps(8443);   // requires an SSLContext — see TLS section below
```

Call `server.stop()` to shut down.

## TLS / HTTPS

`SSLContextFactory` provides two helpers for loading credentials:

**PKCS#12 bundle:**
```java
try (InputStream in = new FileInputStream("server.p12")) {
    SSLContext sslContext = SSLContextFactory.loadPkcs12(in);
}
```

**PEM key + certificate files** (also extracts the CN as the virtual-host name):
```java
SSLContextFactory.SSLInfo sslInfo =
    SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, certFile);

server.addHttpHost(
    sslInfo.getCertificateCommonName(),
    handler,
    sslInfo.getSSLContext());

server.listenHttps(8443);
```

SNI is used to select the right `SSLContext` for each incoming connection. Connections that
present an unknown hostname receive a TLS `unrecognized_name` alert before the handshake
completes.

## Design overview

- **Non-blocking NIO** — a selector-thread pool handles all socket I/O without blocking;
  application handlers run on a separate worker pool.
- **Pipeline stages** — TLS and HTTP are independent, composable layers. For HTTPS the stack
  is: TLS decryption → HTTP parsing → handler → HTTP response → TLS encryption.
- **SNI-aware TLS** — the server inspects the ClientHello to pick the right certificate (and
  reject unknown hostnames with a TLS alert) before completing the handshake.
- **Dual response modes** — `commitBuffered` for small responses (adds `Content-Length`,
  optional gzip); `commitStreamed` for large or dynamic output (chunked encoding,
  backpressure).
- **Virtual hosting** — each hostname has its own handler, TLS context, keep-alive policy,
  and upload policy.

## Coverage Reports

Run this to collect coverage data:
```
bazel coverage //javatest/...
```

Run this to generate an HTML report:
```
genhtml --prefix "$(pwd)" --ignore-errors unsupported,inconsistent,source --synthesize-missing --branch-coverage -o .coverage/ bazel-out/_coverage/_coverage_report.dat
```

Or this to run a simplified text report:
```
bazel run :coverage_report
```

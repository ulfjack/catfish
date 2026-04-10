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
server.addHttpHost("localhost", new HttpVirtualHost(handler));
server.listenHttp(8080);
```

Call `server.stop()` to shut down.

## Request routing

By default, all requests are handled locally by the `HttpHandler` registered for the matching
virtual host. To forward requests to a remote origin instead, use a `ConnectHandler` with
`listenConnectProxy`:

```java
server.listenConnectProxy(8080, new ConnectHandler() {
    @Override
    public ConnectDecision apply(String host, int port) {
        // Forward proxy: client sends absolute URIs (e.g. GET http://example.com/path)
        // or CONNECT requests. Decide per-request: forward, deny, or serve locally.
        return ConnectDecision.tunnel(host, port);
    }
});
```

`ConnectHandler` has two routing methods:

- **`apply(host, port)`** is called for explicit proxy requests: `CONNECT` method and
  absolute-URI requests (e.g. `GET http://host/path`). The client asked to be proxied.
- **`applyLocal(host, port)`** is called for normal requests with relative URIs
  (e.g. `GET /path`). Override this to reverse-proxy selected requests to a remote origin.
  Default: serve locally.

Both methods return a `ConnectDecision`:

| Decision | Effect |
|---|---|
| `serveLocally()` | Handle the request with the local `HttpHandler` (body is buffered) |
| `tunnel(host, port)` | Forward the request to the specified origin (body is streamed) |
| `deny()` | Reject with 403 Forbidden |
| `intercept(host, port, ca)` | MITM-intercept a CONNECT tunnel (mirror the origin's TLS cert) |

### Forward proxy

A forward proxy handles requests where the client explicitly targets a remote origin.
Set `HTTP_PROXY=http://localhost:8080/` on the client side:

```java
server.listenConnectProxy(8080, ConnectHandler.tunnelAll());
```

### Reverse proxy

A reverse proxy forwards normal (relative-URI) requests to a backend server. Override
`applyLocal`:

```java
server.listenConnectProxy(8080, new ConnectHandler() {
    @Override
    public ConnectDecision apply(String host, int port) {
        return ConnectDecision.deny(); // no forward proxying
    }

    @Override
    public ConnectDecision applyLocal(String host, int port) {
        return ConnectDecision.tunnel("backend-server", 9090);
    }
});
```

### MITM interception

For HTTPS traffic, `intercept` terminates the client's TLS connection with a dynamically
generated certificate (mirroring the origin's cert) and forwards the decrypted requests
to the origin:

```java
CertificateAuthority ca = ...;  // your root CA for signing leaf certs
server.listenConnectProxy(8080, ConnectHandler.mitmAll(ca));
```

Each decrypted request inside the tunnel gets its own routing decision via `applyLocal`,
so you can serve some requests locally and forward others.

## TLS / HTTPS

`SSLContextFactory` loads credentials from PEM key + certificate files. The CN
of the certificate is used as the virtual-host name:

```java
SSLInfo sslInfo = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, certFile);

server.addHttpHost(
    sslInfo.certificateCommonName(),
    new HttpVirtualHost(handler).ssl(sslInfo));

server.listenHttps(8443);
```

For loading from non-file sources (e.g. classpath resources in tests), use
`SSLContextFactory.loadPem(InputStream key, InputStream cert)`.

SNI is used to select the right `SSLContext` for each incoming connection. Connections that
present an unknown hostname receive a TLS `unrecognized_name` alert before the handshake
completes.

## Design overview

- **HTTP/1.1 only** — HTTP/1.0 and 0.9 requests are rejected with `505 HTTP Version
  Not Supported` at the request line. Keep-alive, pipelining, and chunked transfer
  encoding are all assumed to be available.
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

Collect coverage data first:
```
bazel coverage //javatest/...
```

Text summary (per-file percentages, sorted ascending):
```
bazel run :coverage_report
```

HTML report written to `.coverage/index.html` (requires `lcov`):
```
bazel run :coverage_html
```

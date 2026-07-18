# Catfish Java HTTP Library

Catfish is a Java library for embedding an HTTP/1.1 server into any JVM application. It provides
low-level control over the HTTP protocol without imposing a framework: no annotation scanning, no
dependency injection, no servlet container (though an optional servlet bridge is available).

Key capabilities: non-blocking I/O, TLS with SNI-based virtual hosting, HTTP/1.1 and HTTP/2,
streaming responses, and keep-alive / compression policies per virtual host.

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
server.listen(
    HttpEndpoint.onAny(8080)
        .addHost("localhost", new HttpVirtualHost(handler)));
```

Call `server.stop()` to shut down.

## Handler thread pool

By default the server runs application handlers on a built-in `ForkJoinPool` fronted by a
bounded queue that rejects excess work with `503 Service Unavailable` under overload.

To take control of the pool that handler tasks run on, pass your own `Executor` to the
constructor. For example, a Java 21+ virtual-thread-per-task executor:

```java
CatfishHttpServer server = new CatfishHttpServer(
    eventListener, Executors.newVirtualThreadPerTaskExecutor());
```

Catfish never shuts the injected executor down — its lifecycle is owned by the application.
An unbounded executor (such as a virtual-thread-per-task executor) never rejects tasks, so
the application is responsible for its own back-pressure; an executor that throws
`RejectedExecutionException` triggers Catfish's `503 Service Unavailable` overload response.

## TLS / HTTPS

Use `HttpsEndpoint` with `SSLContextFactory` to serve over HTTPS:

```java
SSLInfo sslInfo = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, certFile);

server.listen(
    HttpsEndpoint.onAny(8443)
        .addHost("localhost", new HttpVirtualHost(handler), sslInfo));
```

For loading from non-file sources (e.g. classpath resources in tests), use
`SSLContextFactory.loadPem(InputStream key, InputStream cert)`.

SNI is used to select the right `SSLContext` for each incoming connection. Connections that
present an unknown hostname receive a TLS `unrecognized_name` alert before the handshake
completes.

## HTTP/2

Use `Http2Endpoint` for an h2-only HTTPS listener. Clients negotiate HTTP/2 via ALPN
(only `h2` is advertised), so a TLS certificate is required just like `HttpsEndpoint`:

```java
SSLInfo sslInfo = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, certFile);

server.listen(
    Http2Endpoint.onAny(8443)
        .addHost("localhost", new HttpVirtualHost(handler), sslInfo));
```

The same `HttpHandler` is used regardless of protocol version — `commitBuffered` and
`commitStreamed` both work over HTTP/2, with streamed bodies delivered as DATA frames.
SNI selects the right `SSLContext` per hostname, exactly as with HTTP/1.1 over TLS.

## Proxying and CONNECT handling

For forward proxying, reverse proxying, or MITM interception, use a `ConnectHandler` via
the `dispatcher` method on an endpoint. This replaces the default virtual-host routing with
custom request routing logic.

`ConnectHandler` routes three distinct request types:

- **`applyConnect(host, port)`** — `CONNECT` method requests. Only sees host:port (no HTTP
  headers parsed yet). Returns a `ConnectDecision`.
- **`applyProxy(request)`** — absolute-URI forward-proxy requests
  (e.g. `GET http://host/path`). The client explicitly asked to be proxied. Sees full HTTP
  headers. Returns a `RequestAction`. Default: deny.
- **`applyLocal(request)`** — normal requests with relative URIs (e.g. `GET /path`). Sees
  full HTTP headers. Returns a `RequestAction`. Default: deny.

`ConnectDecision` controls how a CONNECT tunnel is handled:

| Decision | Effect |
|---|---|
| `tunnel(host, port)` | Forward raw TCP to the target (no HTTP parsing inside the tunnel) |
| `intercept(host, port, ca)` | MITM-intercept: terminate TLS, mirror origin cert, forward decrypted requests |
| `deny()` | Reject with 403 Forbidden |

`RequestAction` controls how an HTTP request is handled:

| Action | Effect |
|---|---|
| `serveLocally(handler)` | Handle the request with the given `HttpHandler` (body is buffered) |
| `forward(host, port)` | Forward the request to the specified origin (body is streamed) |
| `forward(request)` | Extract host/port/TLS from the request URI and forward |
| `deny()` | Reject with 403 Forbidden |

### Forward proxy

A forward proxy handles requests where the client explicitly targets a remote origin.
Set `HTTP_PROXY=http://localhost:8080/` on the client side:

```java
server.listen(
    HttpEndpoint.onAny(8080)
        .dispatcher(ConnectHandler.tunnelAll()));
```

### Reverse proxy

A reverse proxy forwards normal (relative-URI) requests to a backend server. Override
`applyLocal`:

```java
server.listen(
    HttpEndpoint.onAny(8080)
        .dispatcher(new ConnectHandler() {
            @Override
            public ConnectDecision applyConnect(String host, int port) {
                return ConnectDecision.deny();
            }

            @Override
            public RequestAction applyLocal(HttpRequest request) {
                return RequestAction.forward("backend-server", 9090);
            }
        }));
```

### MITM interception

For HTTPS traffic, `intercept` terminates the client's TLS connection with a dynamically
generated certificate (mirroring the origin's cert) and forwards the decrypted requests
to the origin:

```java
CertificateAuthority ca = ...;  // your root CA for signing leaf certs
server.listen(
    HttpEndpoint.onAny(8080)
        .dispatcher(ConnectHandler.mitmAll(ca)));
```

Each decrypted request inside the tunnel is routed through `applyProxy`, so you can
inspect, modify, record, forward, or serve individual requests locally.

## Design overview

- **HTTP/1.1 and HTTP/2** — over plaintext and TLS, HTTP/1.1 is spoken (HTTP/1.0 and 0.9
  requests are rejected with `505 HTTP Version Not Supported` at the request line; keep-alive,
  pipelining, and chunked transfer encoding are all assumed to be available). HTTP/2 is offered
  on `Http2Endpoint` listeners, negotiated via ALPN (`h2`) over TLS, with HPACK header
  compression and stream multiplexing.
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

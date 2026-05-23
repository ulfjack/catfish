package de.ofahrt.catfish.example;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpEndpoint;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.RequestOutcome;
import de.ofahrt.catfish.ssl.OpensslCertificateAuthority;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Runs a MITM HTTPS proxy that listens on a unix domain socket. CONNECT requests are intercepted:
 * Catfish mints a leaf cert signed by the user-supplied CA, terminates the inner TLS handshake, and
 * serves the inner HTTP request itself.
 *
 * <p>Run with:
 *
 * <pre>{@code
 * bazel run //java/de/ofahrt/catfish/example:mitm_proxy_main -- \
 *     --ca_key=/path/to/ca.key --ca_cert=/path/to/ca.crt
 * }</pre>
 *
 * <p>Then point a client at the socket, e.g.:
 *
 * <pre>{@code
 * curl --proxy "unix:///tmp/catfish-mitm.sock" --cacert /path/to/ca.crt https://example.com/
 * }</pre>
 *
 * <p>Flags:
 *
 * <ul>
 *   <li>{@code --ca_key=PATH} (required) — PEM-encoded CA private key.
 *   <li>{@code --ca_cert=PATH} (required) — PEM-encoded CA certificate.
 *   <li>{@code --socket=PATH} (optional) — listening socket path. Default: {@code
 *       /tmp/catfish-mitm.sock}.
 *   <li>{@code --chain=PATH} (optional) — PEM file with intermediates above {@code --ca_cert},
 *       ordered toward the root. Required only when {@code --ca_cert} is itself an intermediate and
 *       the client needs more certs to chain back to a trusted root.
 * </ul>
 */
public final class MitmProxyMain {

  private MitmProxyMain() {}

  public static void main(String[] args) throws Exception {
    String caKeyArg = null;
    String caCertArg = null;
    String chainArg = null;
    Path socket = Path.of("/tmp/catfish-mitm.sock");
    for (String arg : args) {
      if (arg.startsWith("--ca_key=")) {
        caKeyArg = arg.substring("--ca_key=".length());
      } else if (arg.startsWith("--ca_cert=")) {
        caCertArg = arg.substring("--ca_cert=".length());
      } else if (arg.startsWith("--chain=")) {
        chainArg = arg.substring("--chain=".length());
      } else if (arg.startsWith("--socket=")) {
        socket = Path.of(arg.substring("--socket=".length()));
      } else {
        throw new IllegalArgumentException("Unknown argument: " + arg);
      }
    }
    if (caKeyArg == null || caCertArg == null) {
      throw new IllegalArgumentException("Missing required --ca_key=PATH and/or --ca_cert=PATH");
    }
    Path caKey = Path.of(caKeyArg);
    Path caCert = Path.of(caCertArg);

    Path workDir = Files.createTempDirectory("catfish-mitm-");
    OpensslCertificateAuthority.Builder caBuilder =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir);
    if (chainArg != null) {
      caBuilder.withChainCerts(Path.of(chainArg));
    }
    OpensslCertificateAuthority ca = caBuilder.build();

    Files.deleteIfExists(socket);
    CatfishHttpServer server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {
                System.out.println("[catfish] server stopped");
              }

              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void notifyInternalError(@Nullable Connection id, Throwable throwable) {
                throwable.printStackTrace();
              }
            });
    HttpServerListener connectionLogger =
        new HttpServerListener() {
          @Override
          public void onConnect(UUID connectId, String host, int port) {
            System.out.printf("[connect] %s CONNECT %s:%d%n", connectId, host, port);
          }

          @Override
          public void onConnectFailed(UUID connectId, String host, int port, Exception cause) {
            System.out.printf(
                "[connect] %s CONNECT %s:%d FAILED: %s%n", connectId, host, port, cause);
          }

          @Override
          public void onConnectComplete(UUID connectId, String host, int port) {
            System.out.printf("[connect] %s CONNECT %s:%d closed%n", connectId, host, port);
          }

          @Override
          public void onRequestComplete(
              UUID requestId,
              @Nullable String originHost,
              int originPort,
              @Nullable HttpRequest request,
              RequestOutcome outcome) {
            String method = request != null ? request.getMethod() : "?";
            String uri = request != null ? request.getUri() : "?";
            int status = outcome.response() != null ? outcome.response().getStatusCode() : 0;
            System.out.printf("[request] %s %d %s %s%n", requestId, status, method, uri);
          }
        };
    server.listen(
        HttpEndpoint.onUnixSocket(socket)
            .dispatcher(ConnectHandler.mitmAll(ca))
            .requestListener(connectionLogger));

    System.out.println("[catfish] MITM proxy listening on unix:" + socket);
    System.out.println(
        "Try: curl --proxy \"unix://" + socket + "\" --cacert " + caCert + " https://example.com/");
    System.out.println("Press Ctrl-C to stop.");

    Path socketPath = socket;
    Path workDirPath = workDir;
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    Files.deleteIfExists(socketPath);
                    Files.deleteIfExists(workDirPath);
                  } catch (IOException e) {
                    // ignore on shutdown
                  }
                }));
    Thread.currentThread().join();
  }
}

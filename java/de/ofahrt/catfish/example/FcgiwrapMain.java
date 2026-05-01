package de.ofahrt.catfish.example;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpEndpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.fastcgi.FcgiHandler;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.UploadPolicy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Spawns {@code fcgiwrap} on a TCP port, wires up a Catfish HTTP server that forwards {@code
 * /script} to it via {@link FcgiHandler}, and prints the URL to hit. Runs until killed.
 *
 * <p>Run with: {@code bazel run //java/de/ofahrt/catfish/example:fcgiwrap_main}
 */
public final class FcgiwrapMain {

  private FcgiwrapMain() {}

  public static void main(String[] args) throws Exception {
    Path workDir = Files.createTempDirectory("catfish-fcgiwrap-example");
    Path scriptPath = workDir.resolve("script.sh");
    Files.writeString(
        scriptPath,
        "#!/bin/sh\n"
            + "echo 'Content-Type: text/plain'\n"
            + "echo ''\n"
            + "echo 'Hello from fcgiwrap, served via catfish!'\n"
            + "echo \"method=$REQUEST_METHOD\"\n"
            + "echo \"uri=$REQUEST_URI\"\n"
            + "echo \"query=$QUERY_STRING\"\n"
            + "echo \"contentLength=$CONTENT_LENGTH\"\n"
            + "if [ -n \"$CONTENT_LENGTH\" ] && [ \"$CONTENT_LENGTH\" != \"0\" ]; then\n"
            + "  printf 'body='\n"
            + "  cat\n"
            + "  echo\n"
            + "fi\n");
    Files.setPosixFilePermissions(
        scriptPath,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));

    int fcgiPort = pickPort();
    Process fcgiwrap =
        new ProcessBuilder("fcgiwrap", "-c", "1", "-f", "-s", "tcp:127.0.0.1:" + fcgiPort)
            .redirectErrorStream(true)
            .inheritIO()
            .start();
    waitForPort("127.0.0.1", fcgiPort);
    System.out.println("[fcgiwrap] running on tcp:127.0.0.1:" + fcgiPort);

    int httpPort = 8080;
    CatfishHttpServer server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {
                System.out.println("[catfish] server stopped");
              }

              @Override
              public void portOpened(int port, boolean ssl) {
                System.out.println("[catfish] listening on port " + port);
              }

              @Override
              public void notifyInternalError(@Nullable Connection id, Throwable throwable) {
                throwable.printStackTrace();
              }
            });
    FcgiHandler fcgiHandler =
        new FcgiHandler("127.0.0.1", fcgiPort, "/script", scriptPath.toString());
    HttpVirtualHost host = new HttpVirtualHost(fcgiHandler).uploadPolicy(UploadPolicy.ALLOW);
    server.listen(HttpEndpoint.onAny(httpPort).addHost("default", host));

    System.out.println();
    System.out.println("Open in browser: http://localhost:" + httpPort + "/script?hello=world");
    System.out.println("Or: curl -d 'some-body' http://localhost:" + httpPort + "/script");
    System.out.println();
    System.out.println("Press Ctrl-C to stop.");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  fcgiwrap.destroy();
                  try {
                    Files.deleteIfExists(scriptPath);
                    Files.deleteIfExists(workDir);
                  } catch (IOException e) {
                    // ignore on shutdown
                  }
                }));
    Thread.currentThread().join();
  }

  private static int pickPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void waitForPort(String host, int port) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      try (Socket probe = new Socket()) {
        probe.connect(new InetSocketAddress(host, port), 100);
        return;
      } catch (IOException e) {
        Thread.sleep(50);
      }
    }
    throw new IllegalStateException("fcgiwrap did not start listening on port " + port);
  }
}

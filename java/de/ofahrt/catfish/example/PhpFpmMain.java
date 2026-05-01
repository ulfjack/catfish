package de.ofahrt.catfish.example;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpEndpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.fastcgi.FcgiHandler;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.UploadPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Spawns a private {@code php-fpm} pool on a unix domain socket, wires up a Catfish HTTP server
 * that forwards {@code /hello.php} to it via {@link FcgiHandler}, and prints the URL to hit. Runs
 * until killed.
 *
 * <p>Run with: {@code bazel run //java/de/ofahrt/catfish/example:phpfpm_main -- [php-fpm-binary]}
 *
 * <p>The first argument (optional) is the php-fpm binary name; defaults to {@code php-fpm} on PATH.
 * On Debian-derived systems the binary is often {@code php-fpm8.3} or similar.
 */
public final class PhpFpmMain {

  private PhpFpmMain() {}

  public static void main(String[] args) throws Exception {
    String phpFpmBinary = args.length > 0 ? args[0] : "php-fpm";

    Path workDir = Files.createTempDirectory("catfish-phpfpm-example");
    Path scriptPath = workDir.resolve("hello.php");
    Files.writeString(
        scriptPath,
        "<?php\n"
            + "header('Content-Type: text/plain');\n"
            + "echo \"Hello from PHP, served via catfish!\\n\";\n"
            + "echo 'php_version=' . PHP_VERSION . \"\\n\";\n"
            + "echo 'method=' . ($_SERVER['REQUEST_METHOD'] ?? '') . \"\\n\";\n"
            + "echo 'uri=' . ($_SERVER['REQUEST_URI'] ?? '') . \"\\n\";\n"
            + "echo 'query=' . ($_SERVER['QUERY_STRING'] ?? '') . \"\\n\";\n"
            + "echo 'contentLength=' . ($_SERVER['CONTENT_LENGTH'] ?? '') . \"\\n\";\n"
            + "$body = file_get_contents('php://input');\n"
            + "if (strlen($body) > 0) {\n"
            + "  echo 'body=' . $body . \"\\n\";\n"
            + "}\n");

    Path fpmSocket = workDir.resolve("php-fpm.sock");
    Path fpmConfig = workDir.resolve("php-fpm.conf");
    Path fpmPid = workDir.resolve("php-fpm.pid");
    Path fpmErrorLog = workDir.resolve("php-fpm.log");
    Files.writeString(
        fpmConfig,
        "[global]\n"
            + "pid = "
            + fpmPid
            + "\n"
            + "error_log = "
            + fpmErrorLog
            + "\n"
            + "daemonize = no\n"
            + "\n"
            + "[www]\n"
            + "listen = "
            + fpmSocket
            + "\n"
            + "pm = static\n"
            + "pm.max_children = 2\n"
            + "catch_workers_output = yes\n"
            + "decorate_workers_output = no\n");

    Process phpFpm =
        new ProcessBuilder(phpFpmBinary, "-F", "-y", fpmConfig.toString())
            .redirectErrorStream(true)
            .inheritIO()
            .start();
    waitForSocket(fpmSocket);
    System.out.println("[php-fpm] running on unix:" + fpmSocket);

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
    FcgiHandler fcgiHandler = new FcgiHandler(fpmSocket, "/hello.php", scriptPath.toString());
    HttpVirtualHost host = new HttpVirtualHost(fcgiHandler).uploadPolicy(UploadPolicy.ALLOW);
    server.listen(HttpEndpoint.onAny(httpPort).addHost("default", host));

    System.out.println();
    System.out.println("Open in browser: http://localhost:" + httpPort + "/hello.php?name=world");
    System.out.println("Or: curl -d 'some-body' http://localhost:" + httpPort + "/hello.php");
    System.out.println();
    System.out.println("Press Ctrl-C to stop.");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  phpFpm.destroy();
                  try {
                    Files.deleteIfExists(scriptPath);
                    Files.deleteIfExists(fpmConfig);
                    Files.deleteIfExists(fpmPid);
                    Files.deleteIfExists(fpmErrorLog);
                    Files.deleteIfExists(fpmSocket);
                    Files.deleteIfExists(workDir);
                  } catch (IOException e) {
                    // ignore on shutdown
                  }
                }));
    Thread.currentThread().join();
  }

  /** Polls until the socket file appears, or fails after 10 seconds. */
  private static void waitForSocket(Path socket) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (Files.exists(socket)) {
        return;
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException("php-fpm did not create socket at " + socket);
  }
}

package de.ofahrt.catfish.example;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpEndpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.HttpsEndpoint;
import de.ofahrt.catfish.bridge.ServletHttpHandler;
import de.ofahrt.catfish.bridge.SessionManager;
import de.ofahrt.catfish.fastcgi.FcgiHandler;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.RequestOutcome;
import de.ofahrt.catfish.ssl.SSLContextFactory;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.File;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ExampleMain {

  public static void main(String[] args) throws Exception {
    String sslKeyFile = null;
    String sslCrtFile = null;
    SSLInfo sslInfo = null;
    for (String arg : args) {
      if (arg.startsWith("--ssl_key=")) {
        sslKeyFile = arg.substring(10);
      } else if (arg.startsWith("--ssl_crt=")) {
        sslCrtFile = arg.substring(10);
      } else {
        System.err.println("Cannot parse argument '" + arg + "'");
        System.exit(1);
      }
    }
    if ((sslKeyFile != null) && (sslCrtFile != null)) {
      sslInfo = SSLContextFactory.loadPemKeyAndCrtFiles(new File(sslKeyFile), new File(sslCrtFile));
    }

    CatfishHttpServer server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {
                System.out.println("[CATFISH] Server stopped.");
              }

              @Override
              public void portOpened(int port, boolean ssl) {
                System.out.println(
                    "[CATFISH] Opening socket on port " + port + (ssl ? " (ssl)" : ""));
              }

              @Override
              public void notifyInternalError(@Nullable Connection id, Throwable throwable) {
                throwable.printStackTrace();
              }
            });
    HttpServerListener errorLogger =
        new HttpServerListener() {
          @Override
          public void onRequestComplete(
              UUID requestId,
              @Nullable String originHost,
              int originPort,
              @Nullable HttpRequest request,
              RequestOutcome outcome) {
            if (outcome.response() != null && (outcome.response().getStatusCode() / 100) == 5) {
              System.out.printf(
                  "[CATFISH] %d %s\n",
                  Integer.valueOf(outcome.response().getStatusCode()),
                  outcome.response().getStatusMessage());
            }
          }
        };

    HttpHandler handler =
        new ServletHttpHandler.Builder()
            .withSessionManager(new SessionManager())
            .exact(
                "/hello.php",
                new FcgiHandler(
                    "localhost", 12345, "/hello.php", "/home/ulfjack/Projects/catfish/hello.php"))
            .exact("/post", new CheckPostHandler())
            .exact("/", new TraceHandler())
            .exact("/large", new LargeResponseHandler(16536))
            .directory("/public/", new DirectoryHandler("/tmp/public/"))
            .build();

    // Keep-alive and compression policies must be set before adding a host.
    HttpEndpoint httpEndpoint =
        HttpEndpoint.onAny(8080)
            .addHost("localhost", new HttpVirtualHost(handler))
            .requestListener(errorLogger);
    server.listen(httpEndpoint);
    if (sslInfo != null) {
      HttpsEndpoint httpsEndpoint =
          HttpsEndpoint.onAny(8081)
              .addHost(
                  Objects.requireNonNull(sslInfo.certificateCommonName()),
                  new HttpVirtualHost(handler),
                  sslInfo)
              .requestListener(errorLogger);
      server.listen(httpsEndpoint);
    }
  }
}

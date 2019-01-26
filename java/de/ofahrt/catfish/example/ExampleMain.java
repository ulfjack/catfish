package de.ofahrt.catfish.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import javax.net.ssl.SSLContext;
import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpServerListener;
import de.ofahrt.catfish.bridge.ServletHttpHandler;
import de.ofahrt.catfish.bridge.SessionManager;
import de.ofahrt.catfish.fastcgi.FcgiServlet;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.BasicHttpHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.ssl.SSLContextFactory;
import de.ofahrt.catfish.ssl.SSLContextFactory.SSLInfo;

public class ExampleMain {

  public static void main(String[] args) throws Exception {
    String sslKeyFile = null;
    String sslCrtFile = null;
    SSLContext sslContext = null;
    String domainName = null;
    for (String arg : args) {
      if (arg.startsWith("--ssl=")) {
        try (InputStream sslCert = new FileInputStream(arg.substring(6))) {
          sslContext = SSLContextFactory.loadPkcs12(sslCert);
        }
      } else if (arg.startsWith("--ssl_key=")) {
        sslKeyFile = arg.substring(10);
      } else if (arg.startsWith("--ssl_crt=")) {
        sslCrtFile = arg.substring(10);
      } else {
        System.err.println("Cannot parse argument '" + arg + "'");
        System.exit(1);
      }
    }
    if ((sslKeyFile != null) && (sslCrtFile != null)) {
      SSLInfo sslInfo = SSLContextFactory.loadPemKeyAndCrtFiles(new File(sslKeyFile), new File(sslCrtFile));
      sslContext = sslInfo.getSSLContext();
      domainName = sslInfo.getCertificateCommonName();
    }

    CatfishHttpServer server = new CatfishHttpServer(new NetworkEventListener() {
      @Override
      public void shutdown() {
        System.out.println("[CATFISH] Server stopped.");
      }

      @Override
      public void portOpened(int port, boolean ssl) {
        System.out.println("[CATFISH] Opening socket on port "+port+(ssl ? " (ssl)" : ""));
      }

      @Override
      public void notifyInternalError(Connection id, Throwable throwable) {
        throwable.printStackTrace();
      }
    });
    server.addRequestListener(new HttpServerListener() {
      @Override
      public void notifySent(Connection connection, HttpRequest request, HttpResponse response, int bytesSent) {
        if ((response.getStatusCode() / 100) == 5) {
          System.out.printf("[CATFISH] %d %s\n",
              Integer.valueOf(response.getStatusCode()), response.getStatusMessage());
        }
      }
    });

    HttpHandler handler = new ServletHttpHandler.Builder()
        .withSessionManager(new SessionManager())
        .exact("/hello.php", new FcgiServlet())
        .exact("/post", new CheckPostHandler())
        .exact("/", new TraceHandler())
        .exact("/large", new LargeResponseHandler(16536))
        .directory("/public/", new DirectoryHandler("/tmp/public/"))
        .build();
    handler = new BasicHttpHandler(handler);

    server.addHttpHost("localhost", handler, null);
    server.setKeepAliveAllowed(true);
    server.setCompressionAllowed(false);
    server.listenHttp(8080);
    if (sslContext != null) {
      // TODO: This doesn't work for wildcard certificates.
      server.addHttpHost(domainName, handler, sslContext);
      server.listenHttps(8081);
    }
  }
}

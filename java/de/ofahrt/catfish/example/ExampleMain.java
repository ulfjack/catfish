package de.ofahrt.catfish.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import javax.net.ssl.SSLContext;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpServerListener;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.api.Connection;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.fastcgi.FcgiServlet;
import de.ofahrt.catfish.servlets.CheckCompression;
import de.ofahrt.catfish.servlets.CheckPost;
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

    CatfishHttpServer server = new CatfishHttpServer(new HttpServerListener() {
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

      @Override
      public void notifyRequest(Connection connection, HttpRequest request, HttpResponse response) {
        if ((response.getStatusCode() / 100) == 5) {
          System.out.printf("[CATFISH] %d %s\n",
              Integer.valueOf(response.getStatusCode()), response.getStatusMessage());
        }
      }
    });

    HttpVirtualHost.Builder dir = new HttpVirtualHost.Builder()
       .exact("/hello.php", new FcgiServlet())
       .exact("/post", new CheckPost())
       .exact("/", new CheckCompression())
       .exact("/large", new LargeResponseServlet(16536));

    server.addHttpHost("localhost", dir.build());
    server.setKeepAliveAllowed(true);
    server.setCompressionAllowed(false);
    server.listenHttp(8080);
    if (sslContext != null) {
      // TODO: This doesn't work for wildcard certificates.
      server.addHttpHost(domainName, dir.withSSLContext(sslContext).build());
      server.listenHttps(8081);
    }
  }
}

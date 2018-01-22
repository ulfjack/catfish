package de.ofahrt.catfish.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import javax.net.ssl.SSLContext;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.CatfishHttpServer.EventType;
import de.ofahrt.catfish.CatfishHttpServer.ServerListener;
import de.ofahrt.catfish.ConnectionId;
import de.ofahrt.catfish.VirtualHost;
import de.ofahrt.catfish.fastcgi.FcgiServlet;
import de.ofahrt.catfish.servlets.CheckCompression;
import de.ofahrt.catfish.servlets.CheckPost;
import de.ofahrt.catfish.utils.SSLContextFactory;
import de.ofahrt.catfish.utils.SSLContextFactory.SSLInfo;

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

    CatfishHttpServer server = new CatfishHttpServer(new ServerListener() {
      @Override
      public void shutdown() {
        System.out.println("[CATFISH] Server stopped.");
      }

      @Override
      public void openPort(int port, boolean ssl) {
        System.out.println("[CATFISH] Opening socket on port "+port+(ssl ? " (ssl)" : ""));
      }

      @Override
      public void event(ConnectionId id, EventType event) {
        // No output for now.
      }

      @Override
      public void notifyException(ConnectionId id, Throwable throwable) {
        throwable.printStackTrace();
      }

      @Override
      public void notifyBadRequest(ConnectionId id, Throwable throwable) {
        throwable.printStackTrace();
      }

      @Override
      public void notifyBadRequest(ConnectionId id, String msg) {
        System.out.println("BAD REQUEST: " + msg);
      }
    });

    VirtualHost.Builder dir = new VirtualHost.Builder()
       .exact("/hello.php", new FcgiServlet())
       .exact("/post", new CheckPost())
       .directory("/", new CheckCompression());

    server.addVirtualHost("localhost", dir.build());
    server.setKeepAliveAllowed(true);
    server.listenHttp(8080);
    if (sslContext != null) {
      // TODO: This doesn't work for wildcard certificates.
      server.addVirtualHost(domainName, dir.withSSLContext(sslContext).build());
      server.listenHttps(8081);
    }
  }
}

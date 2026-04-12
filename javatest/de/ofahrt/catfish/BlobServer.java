package de.ofahrt.catfish;

import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.ssl.SSLContextFactory;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;

/**
 * Minimal server for external benchmarking (h2load, wrk, ab). Serves a 1 KB blob on HTTP/1.1 (port
 * 8090) and HTTP/2 (port 8443).
 */
public class BlobServer {

  public static void main(String[] args) throws Exception {
    byte[] body = new byte[1024];
    for (int i = 0; i < body.length; i++) {
      body[i] = (byte) 'A';
    }
    HttpResponse blob =
        StandardResponses.OK
            .withHeaderOverrides(HttpHeaders.of("Content-Type", "application/octet-stream"))
            .withBody(body);

    HttpVirtualHost host = new HttpVirtualHost((conn, req, writer) -> writer.commitBuffered(blob));

    CatfishHttpServer server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void portOpened(int port, boolean ssl) {
                System.out.println("Listening on port " + port + (ssl ? " (TLS)" : ""));
              }

              @Override
              public void shutdown() {}

              @Override
              public void notifyInternalError(@Nullable Connection id, Throwable t) {
                t.printStackTrace();
              }
            });
    server.listen(HttpEndpoint.onLocalhost(8090).addHost("localhost", host));

    SSLInfo sslInfo;
    try (InputStream key =
            BlobServer.class.getClassLoader().getResourceAsStream("localhost-key.pem");
        InputStream cert =
            BlobServer.class.getClassLoader().getResourceAsStream("localhost-cert.pem")) {
      sslInfo = SSLContextFactory.loadPem(key, cert);
    }
    server.listen(Http2Endpoint.onLocalhost(8443).addHost("localhost", host, sslInfo));

    System.out.println("Server ready. Press Ctrl+C to stop.");
    System.out.println("  HTTP/1.1: http://localhost:8090/");
    System.out.println("  HTTP/2:   https://localhost:8443/");
  }
}

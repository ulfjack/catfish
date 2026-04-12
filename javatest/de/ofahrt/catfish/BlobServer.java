package de.ofahrt.catfish;

import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import org.jspecify.annotations.Nullable;

/** Minimal server for external benchmarking (h2load, wrk, ab). Serves a 1 KB blob on port 8080. */
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

    CatfishHttpServer server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void portOpened(int port, boolean ssl) {
                System.out.println("Listening on port " + port);
              }

              @Override
              public void shutdown() {}

              @Override
              public void notifyInternalError(@Nullable Connection id, Throwable t) {
                t.printStackTrace();
              }
            });
    server.listen(
        HttpEndpoint.onLocalhost(8090)
            .addHost(
                "localhost",
                new HttpVirtualHost((conn, req, writer) -> writer.commitBuffered(blob))));
    System.out.println("Server ready. Press Ctrl+C to stop.");
  }
}

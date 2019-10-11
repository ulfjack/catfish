package de.ofahrt.catfish.client;

import javax.net.ssl.SSLContext;
import de.ofahrt.catfish.internal.CoreHelper;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.utils.HttpConnectionHeader;

public class ClientMainForTesting {
  private static final boolean USE_SSL = true;

  public static void main(String[] args) throws Exception {
    CatfishHttpClient client = new CatfishHttpClient(new NetworkEventListener() {
      @Override
      public void portOpened(int port, boolean ssl) {
      }

      @Override
      public void shutdown() {
      }

      @Override
      public void notifyInternalError(Connection connection, Throwable throwable) {
        throwable.printStackTrace();
      }
    });
    try {
      HttpRequest request = new SimpleHttpRequest.Builder()
          .setVersion(HttpVersion.HTTP_1_1)
          .setMethod(HttpMethodName.GET)
          .setUri("/")
          .addHeader(HttpHeaderName.HOST, "www.example.com")
          .addHeader(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE)
          .build();
      HttpResponse response;
      if (USE_SSL) {
        SSLContext sslContext = SSLContext.getDefault();
        response = client.send("www.datayoureat.com", 443, sslContext, request).get();
      } else {
        response = client.send("www.example.com", 80, null, request).get();
      }
      System.out.println(CoreHelper.responseToString(response));
    } finally {
      client.shutdown();
    }
  }
}

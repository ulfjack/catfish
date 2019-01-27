package de.ofahrt.catfish.client;

import de.ofahrt.catfish.internal.CoreHelper;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.network.NetworkEventListener;

public class ClientMainForTesting {

  public static void main(String[] args) throws Exception {
    CatfishHttpClient client = new CatfishHttpClient(new NetworkEventListener() {
      @Override
      public void portOpened(int port, boolean ssl) {
      }

      @Override
      public void shutdown() {
      }
    });
    HttpRequest request = new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod(HttpMethodName.GET)
        .setUri("/")
        .addHeader(HttpHeaderName.HOST, "www.example.com")
//        .addHeader(HttpHeaderName.CONNECTION, "close")
        .setBody(new HttpRequest.InMemoryBody(new byte[0]))
        .build();
    HttpResponse response = client.send("www.example.com", request);
    System.out.println(CoreHelper.responseToString(response));
    client.shutdown();
  }
}

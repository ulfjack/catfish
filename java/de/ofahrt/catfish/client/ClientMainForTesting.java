package de.ofahrt.catfish.client;

import de.ofahrt.catfish.internal.CoreHelper;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;

public class ClientMainForTesting {
  private static final boolean USE_SSL = true;

  private static final Set<String> PROTOCOL_WHITELIST = new HashSet<>(Arrays.asList("TLSv1.2"));
  private static final Set<String> CIPHER_WHITELIST =
      new HashSet<>(
          Arrays.asList(
              "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"));

  public static void main(String[] args) throws Exception {
    CatfishHttpClient client = new CatfishHttpClient(new LoggingNetworkEventListener());
    try {
      HttpRequest request =
          new SimpleHttpRequest.Builder()
              .setVersion(HttpVersion.HTTP_1_1)
              .setMethod(HttpMethodName.GET)
              .setUri("/")
              .addHeader(HttpHeaderName.HOST, "www.example.com")
              .addHeader(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE)
              .build();
      HttpResponse response;
      if (USE_SSL) {
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(null, new TrustManager[] {new LoggingTrustManager()}, null);
        SSLParameters defaultParameters = sslContext.getDefaultSSLParameters();
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setServerNames(
            Arrays.asList(new SNIHostName(request.getHeaders().get(HttpHeaderName.HOST))));
        sslParameters.setProtocols(
            SslHelper.filter(defaultParameters.getProtocols(), PROTOCOL_WHITELIST));
        sslParameters.setCipherSuites(
            SslHelper.filter(defaultParameters.getCipherSuites(), CIPHER_WHITELIST));
        response = client.send("www.example.com", 443, sslContext, sslParameters, request).get();
      } else {
        response = client.send("www.example.com", 80, null, null, request).get();
      }
      System.out.println(CoreHelper.responseToString(response));
    } finally {
      client.shutdown();
    }
  }
}

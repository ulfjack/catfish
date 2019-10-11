package de.ofahrt.catfish.client;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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

  private static final Set<String> PROTOCOL_WHITELIST = new HashSet<>(Arrays.asList("TLSv1.2"));
  private static final Set<String> CIPHER_WHITELIST = new HashSet<>(Arrays.asList(
      "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
      "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"
  ));

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
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        X509TrustManager trustManager = new X509TrustManager() {
          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            System.out.printf("checkClientTrusted(%s, %s)\n", chain, authType);
          }

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            System.out.printf("checkServerTrusted(%s, %s)\n", Arrays.toString(chain), authType);
          }

          @Override
          public X509Certificate[] getAcceptedIssuers() {
            System.out.printf("getAcceptedIssuers()\n");
            return null;
          }
        };
        sslContext.init(null, new TrustManager[] { trustManager }, null);
        SSLParameters defaultParameters = sslContext.getDefaultSSLParameters();
        printParameters(defaultParameters);
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setServerNames(Arrays.asList(new SNIHostName(request.getHeaders().get(HttpHeaderName.HOST))));
        sslParameters.setProtocols(filter(defaultParameters.getProtocols(), PROTOCOL_WHITELIST));
        sslParameters.setCipherSuites(filter(defaultParameters.getCipherSuites(), CIPHER_WHITELIST));
        response = client.send("www.example.com", 443, sslContext, sslParameters, request).get();
      } else {
        response = client.send("www.example.com", 80, null, null, request).get();
      }
      System.out.println(CoreHelper.responseToString(response));
    } finally {
      client.shutdown();
    }
  }

  private static String[] filter(String[] values, Set<String> allowedValues) {
    List<String> temporary = new ArrayList<>();
    for (String value : values) {
      if (allowedValues.contains(value)) {
        temporary.add(value);
      }
    }
    return temporary.toArray(new String[0]);
  }

  private static void printParameters(SSLParameters parameters) {
    System.out.println("Identification algorithm = " + parameters.getEndpointIdentificationAlgorithm());
    System.out.println("Need client auth = " + parameters.getNeedClientAuth());
    System.out.println("Use cipher suites order = " + parameters.getUseCipherSuitesOrder());
    System.out.println("Want client auth = " + parameters.getWantClientAuth());
    System.out.println("Algorithm constraints = " + parameters.getAlgorithmConstraints());
    System.out.println("Protocols = " + Arrays.toString(parameters.getProtocols()));
    System.out.println("Cipher suites = " + Arrays.toString(parameters.getCipherSuites()));
  }
}

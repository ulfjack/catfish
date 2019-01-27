package de.ofahrt.catfish.spider;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import de.ofahrt.catfish.client.legacy.HttpConnection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;

public final class Spider {
  private final String host;
  private final int port = 80;
  private final String entryPoint = "/";

  private final Set<String> visitedPages = new ConcurrentSkipListSet<>();

  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(1, 1, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

  private Spider(Builder builder) {
    this.host = builder.host;
  }

  public void start() {
    visitedPages.add(entryPoint);
    executor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          retrieve(entryPoint);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  private void retrieve(String page) throws IOException {
    HttpConnection connection = HttpConnection.connect(host, port);
    HttpRequest request = StandardRequests.get(new URL("http://" + host + ":" + port + page))
        .withHeaderOverrides(HttpHeaders.of(HttpHeaderName.CONNECTION, "close"));
    HttpResponse response = connection.send(request);
//    System.out.println(response.getStatusCode());
//    System.out.println(response.getContentAsString());
    connection.close();
    String htmlContent = new String(response.getBody(), StandardCharsets.UTF_8);
    Document document = new HtmlParser().parse(htmlContent);
    NodeList nodes = document.getElementsByTagName("a");
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      NamedNodeMap attr = node.getAttributes();
      System.out.println(attr.getNamedItem("href").getNodeValue());
    }
  }

  public void finish() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(100, TimeUnit.SECONDS);
  }

  public static void main(String[] args) throws InterruptedException {
    Spider spider = new Builder().setHost("www.conquer-space.net").build();
    spider.start();
    spider.finish();
  }

  public static final class Builder {
    private String host;

    public Spider build() {
      return new Spider(this);
    }

    public Builder setHost(String host) {
      this.host = host;
      return this;
    }
  }
}

package de.ofahrt.catfish;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import de.ofahrt.catfish.api.Connection;
import de.ofahrt.catfish.api.HttpHeaderName;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.api.MalformedRequestException;
import de.ofahrt.catfish.api.StandardResponses;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.utils.HttpConnectionHeader;

/**
 * A <code>CatfishHttpServer</code> manages a HTTP-Server.
 */
public final class CatfishHttpServer {
  private static final String MIME_APPLICATION_JAVASCRIPT = "application/javascript";
  private static final String MIME_APPLICATION_XHTML_AND_XML = "application/xhtml+xml";
  private static final String MIME_APPLICATION_XML = "application/xml";
  private static final String MIME_APPLICATION_XML_DTD = "application/xml-dtd";

  private static final String MIME_TEXT_CSS = "text/css";
  private static final String MIME_TEXT_CSV = "text/csv";
  private static final String MIME_TEXT_HTML  = "text/html";
  private static final String MIME_TEXT_PLAIN = "text/plain";
  private static final String MIME_TEXT_RICHTEXT = "text/richtext";
  private static final String MIME_TEXT_RTF = "text/rtf";
  private static final String MIME_TEXT_XML = "text/xml";

  private static final Set<String> COMPRESSION_WHITELIST = constructCompressionWhitelist();

  private static Set<String> constructCompressionWhitelist() {
    HashSet<String> result = new HashSet<>();
    result.add(MIME_APPLICATION_JAVASCRIPT);
    result.add(MIME_APPLICATION_XHTML_AND_XML);
    result.add(MIME_APPLICATION_XML);
    result.add(MIME_APPLICATION_XML_DTD);

    result.add(MIME_TEXT_CSS);
    result.add(MIME_TEXT_CSV);
    result.add(MIME_TEXT_HTML);
    result.add(MIME_TEXT_PLAIN);
    result.add(MIME_TEXT_RICHTEXT);
    result.add(MIME_TEXT_RTF);
    result.add(MIME_TEXT_XML);
    return Collections.unmodifiableSet(result);
  }

  private final class DefaultResponsePolicy implements ResponsePolicy {
    @Override
    public boolean shouldKeepAlive(HttpRequest request) {
      return mayKeepAlive && HttpConnectionHeader.mayKeepAlive(request);
    }

    @Override
    public boolean shouldCompress(HttpRequest request, String mimeType) {
      return mayCompress && COMPRESSION_WHITELIST.contains(mimeType) && supportGzipCompression(request);
    }

    private boolean supportGzipCompression(HttpRequest request) {
      String temp = request.getHeaders().get(HttpHeaderName.ACCEPT_ENCODING);
      if (temp != null) {
        if (temp.toLowerCase(Locale.US).indexOf("gzip") >= 0) {
          return true;
        }
      }
      // Some firewalls disable compression, but leave a header like this in place of the original one:
      // "~~~~~~~~~~~~~~~" -> "~~~~~ ~~~~~~~"
      // "---------------" -> "----- -------"
      // Norton sometimes eats the HTTP response if we compress anyway.
      return false;
    }
  }

  interface RequestCallback extends Runnable {
    void reject();
  }

  private final HttpServerListener serverListener;

  private volatile HttpVirtualHost defaultDomain;

  private final ConcurrentHashMap<String, HttpVirtualHost> hosts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> aliases = new ConcurrentHashMap<>();

  private volatile boolean mayCompress = true;
  private volatile boolean mayKeepAlive = false;

  private final ArrayList<RequestListener> listeners = new ArrayList<>();
  private final NioEngine engine;

  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          8, 8, 1L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
          new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
              return new Thread(r, "catfish-worker-" + threadNumber.getAndIncrement());
            }
          });

  public CatfishHttpServer(HttpServerListener serverListener) throws IOException {
    this.serverListener = serverListener;
    // TODO: This implements tail drop.
    executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
      @Override
      public void rejectedExecution(Runnable task, ThreadPoolExecutor actualExecutor) {
        if (task instanceof RequestCallback) {
          ((RequestCallback) task).reject();
        }
      }
    });
    this.engine = new NioEngine(this);
  }

  HttpServerListener getServerListener() {
    return serverListener;
  }

  public void addHttpHost(String name, HttpVirtualHost virtualHost) {
    if (defaultDomain == null) {
      defaultDomain = virtualHost;
    }
    hosts.put(name, virtualHost);
  }

  public void addAlias(String alias, String name) {
    aliases.put(alias, name);
  }

  public void setCompressionAllowed(boolean how) {
    mayCompress = how;
  }

  public boolean isCompressionAllowed() {
    return mayCompress;
  }

  public void setKeepAliveAllowed(boolean how) {
    mayKeepAlive = how;
  }

  public boolean isKeepAliveAllowed() {
    return mayKeepAlive;
  }

  public void addRequestListener(RequestListener l) {
    listeners.add(l);
  }

  public void removeRequestListener(RequestListener l) {
    listeners.remove(l);
  }

  public String getServerName() {
    return "Catfish/11.0";
  }

  void notifySent(Connection connection, HttpRequest request, HttpResponse response, int amount) {
    serverListener.notifyRequest(connection, request, response);
    for (int i = 0; i < listeners.size(); i++) {
      RequestListener l = listeners.get(i);
      try {
        l.notifySent(connection, request, response, amount);
      } catch (Throwable error) {
        error.printStackTrace();
      }
    }
  }

  void notifyInternalError(Connection connection, HttpRequest req, Throwable e) {
    e.printStackTrace();
    for (int i = 0; i < listeners.size(); i++) {
      RequestListener l = listeners.get(i);
      try {
        l.notifyInternalError(connection, req, e);
      } catch (Throwable error) {
        error.printStackTrace();
      }
    }
  }

  SSLContext getSSLContext(String host) {
    HttpVirtualHost domain = findVirtualHost(host);
    return domain == null ? defaultDomain.getSSLContext() : domain.getSSLContext();
  }

  ResponsePolicy getResponsePolicy() {
    return new DefaultResponsePolicy();
  }

  void queueRequest(Connection connection, HttpRequest request, HttpResponseWriter responseWriter) {
    executor.execute(new RequestCallback() {
      @Override
      public void run() {
        createResponse(connection, request, responseWriter);
      }

      @Override
      public void reject() {
        HttpResponse responseToWrite = StandardResponses.SERVICE_UNAVAILABLE;
        responseWriter.commitBuffered(responseToWrite);
      }
    });
  }

  void createResponse(Connection connection, HttpRequest request, HttpResponseWriter writer) {
    if (request.getHeaders().get(HttpHeaderName.EXPECT) != null) {
      writer.commitBuffered(StandardResponses.EXPECTATION_FAILED);
    } else if (request.getHeaders().get(HttpHeaderName.CONTENT_ENCODING) != null) {
      writer.commitBuffered(StandardResponses.UNSUPPORTED_MEDIA_TYPE);
    } else if ("*".equals(request.getUri())) {
      writer.commitBuffered(StandardResponses.BAD_REQUEST);
    } else {
      HttpHandler handler;
      try {
        handler = determineHttpHandler(request);
      } catch (MalformedRequestException e) {
        writer.commitBuffered(e.getErrorResponse());
        return;
      }
      try {
        handler.handle(connection, request, writer);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private HttpHandler determineHttpHandler(HttpRequest request) throws MalformedRequestException {
    String host = request.getHeaders().get(HttpHeaderName.HOST);
    if (host != null) {
      if (host.indexOf(':') >= 0) {
        host = host.substring(0, host.indexOf(':'));
      }
    }
    HttpVirtualHost virtualHost = findVirtualHost(host);
    String path = getPath(request);
    return virtualHost.determineHttpHandler(path);
  }

  private String getPath(HttpRequest request) throws MalformedRequestException {
    try {
      return new URI(request.getUri()).getPath();
    } catch (URISyntaxException e) {
      throw new MalformedRequestException(StandardResponses.BAD_REQUEST);
    }
  }

  private HttpVirtualHost findVirtualHost(String hostName) {
    if (hostName == null) {
      return defaultDomain;
    }
    if (aliases.containsKey(hostName)) {
      hostName = aliases.get(hostName);
    }
    HttpVirtualHost virtualHost = hosts.get(hostName);
    return virtualHost == null ? defaultDomain : virtualHost;
  }

  public void stop() throws InterruptedException {
    engine.stop();
    serverListener.shutdown();
  }

  public void listenHttpLocal(int port) throws IOException, InterruptedException {
    engine.startLocal(port, /*ssl=*/false);
  }

  public void listenHttp(int port) throws IOException, InterruptedException {
    engine.start(port, /*ssl=*/false);
  }

  public void listenHttps(int port) throws IOException, InterruptedException {
    engine.start(port, /*ssl=*/true);
  }

  public int getOpenConnections() {
    return engine.getOpenConnections();
  }
}

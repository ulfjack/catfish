package de.ofahrt.catfish;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import de.ofahrt.catfish.model.Connection;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.upload.SimpleUploadPolicy;

/**
 * A <code>CatfishHttpServer</code> manages a HTTP-Server.
 */
public final class CatfishHttpServer {
  interface RequestCallback extends Runnable {
    void reject();
  }

  private final HttpServerListener serverListener;

  private final ConcurrentHashMap<String, HttpVirtualHost> hosts = new ConcurrentHashMap<>();

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
    this.engine = new NioEngine(serverListener);
  }

  public void addHttpHost(String name, HttpHandler handler, SSLContext sslContext) {
    ResponsePolicy responsePolicy = new DefaultResponsePolicy(mayKeepAlive, mayCompress);
    UploadPolicy uploadPolicy = new SimpleUploadPolicy(1024 * 1024);
    hosts.put(name, new HttpVirtualHost(handler, responsePolicy, uploadPolicy, sslContext));
  }

  public void addHttpHost(String name, UploadPolicy uploadPolicy, HttpHandler handler, SSLContext sslContext) {
    ResponsePolicy responsePolicy = new DefaultResponsePolicy(mayKeepAlive, mayCompress);
    hosts.put(name, new HttpVirtualHost(handler, responsePolicy, uploadPolicy, sslContext));
  }

  public void setCompressionAllowed(boolean compressionAllowed) {
    mayCompress = compressionAllowed;
  }

  public void setKeepAliveAllowed(boolean keepAliveAllowed) {
    mayKeepAlive = keepAliveAllowed;
  }

  public void addRequestListener(RequestListener l) {
    listeners.add(l);
  }

  public void removeRequestListener(RequestListener l) {
    listeners.remove(l);
  }

  public String getServerName() {
    return "Catfish/12.0";
  }

  @SuppressWarnings("deprecation") // for notifyRequest
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

  SSLContext getSSLContext(String host) {
    HttpVirtualHost domain = host == null ? null : hosts.get(host);
    return domain != null ? domain.getSSLContext() : null;
  }

  void queueRequest(HttpHandler httpHandler, Connection connection, HttpRequest request, HttpResponseWriter responseWriter) {
    executor.execute(new RequestCallback() {
      @Override
      public void run() {
        try {
          httpHandler.handle(connection, request, responseWriter);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void reject() {
        HttpResponse responseToWrite = StandardResponses.SERVICE_UNAVAILABLE;
        responseWriter.commitBuffered(responseToWrite);
      }
    });
  }

  HttpVirtualHost determineHttpVirtualHost(String hostHeader) {
    if (hostHeader == null) {
      return null;
    }
    if (hostHeader.indexOf(':') >= 0) {
      hostHeader = hostHeader.substring(0, hostHeader.indexOf(':'));
    }
    if (hostHeader.endsWith(".localhost")) {
      hostHeader = hostHeader.substring(0, hostHeader.length() - ".localhost".length());
    }
    return hosts.get(hostHeader);
  }

  public void stop() throws InterruptedException {
    engine.shutdown();
  }

  public void listenHttpLocal(int port) throws IOException, InterruptedException {
    engine.listenLocalhost(port, new HttpServerHandler(this, /*ssl=*/false));
  }

  public void listenHttp(int port) throws IOException, InterruptedException {
    engine.listenAll(port, new HttpServerHandler(this, /*ssl=*/false));
  }

  public void listenHttps(int port) throws IOException, InterruptedException {
    engine.listenAll(port, new HttpServerHandler(this, /*ssl=*/true));
  }

  public int getOpenConnections() {
    return engine.getOpenConnections();
  }
}

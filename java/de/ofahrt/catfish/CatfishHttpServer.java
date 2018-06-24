package de.ofahrt.catfish;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.servlet.http.HttpServletResponse;

import de.ofahrt.catfish.api.Connection;
import de.ofahrt.catfish.api.HttpHeaderName;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.api.HttpResponseWriter;
import de.ofahrt.catfish.api.MalformedRequestException;
import de.ofahrt.catfish.bridge.RequestImpl;
import de.ofahrt.catfish.bridge.ResponseImpl;
import de.ofahrt.catfish.bridge.ResponsePolicy;
import de.ofahrt.catfish.bridge.SessionManager;

/**
 * A <code>CatfishHttpServer</code> manages a HTTP-Server.
 */
public final class CatfishHttpServer {

  interface RequestCallback extends Runnable {
    void reject();
  }

  private final HttpServerListener serverListener;

  private volatile InternalVirtualHost defaultDomain;

  private final ConcurrentHashMap<String, InternalVirtualHost> hosts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> aliases = new ConcurrentHashMap<>();

  private volatile boolean mayCompress = true;
  private volatile boolean mayKeepAlive = false;

  private final ArrayList<RequestListener> listeners = new ArrayList<>();
  private final NioEngine engine;

  private final SessionManager sessionManager = new SessionManager();

  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(8, 8, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

  public CatfishHttpServer(HttpServerListener serverListener) throws IOException {
    this.serverListener = serverListener;
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

  public void addHead(String name, Directory dir) {
    addHead(name, dir, null);
  }

  public void addHead(String name, Directory dir, SSLContext sslContext) {
    InternalVirtualHost domain = new DirectoryVirtualHost(dir, sslContext);
    if (defaultDomain == null) {
      defaultDomain = domain;
    }
    hosts.put(name, domain);
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
    InternalVirtualHost domain = findVirtualHost(host);
    return domain == null ? defaultDomain.getSSLContext() : domain.getSSLContext();
  }

  void createResponse(Connection connection, HttpRequest request, HttpResponseWriter writer) {
    if (request.getHeaders().get(HttpHeaderName.EXPECT) != null) {
      writer.commitBuffered(HttpResponse.EXPECTATION_FAILED);
    } else if ("*".equals(request.getUri())) {
      writer.commitBuffered(HttpResponse.BAD_REQUEST);
    } else {
      evaluateServletRequest(connection, request, writer);
    }
  }

  private void evaluateServletRequest(Connection connection, HttpRequest request, HttpResponseWriter writer) {
    RequestImpl servletRequest;
    try {
      servletRequest = new RequestImpl(request, connection, sessionManager, new ResponsePolicy() {
        @Override
        public boolean shouldCompress(String mimeType) {
          return mayCompress && CoreHelper.shouldCompress(mimeType);
        }
      }, writer);
    } catch (MalformedRequestException e) {
      writer.commitBuffered(e.getErrorResponse());
      return;
    }
    ResponseImpl response = servletRequest.getResponse();
    FilterDispatcher dispatcher = determineDispatcher(servletRequest);
    try {
      dispatcher.dispatch(servletRequest, response);
    } catch (FileNotFoundException e) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
    } catch (Exception e) {
      e.printStackTrace();
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
    try {
      response.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private FilterDispatcher determineDispatcher(RequestImpl request) {
    String host = request.getHeader(HttpHeaderName.HOST);
    if (host != null) {
      if (host.indexOf(':') >= 0) {
        host = host.substring(0, host.indexOf(':'));
      }
    }
    InternalVirtualHost virtualHost = findVirtualHost(host);
    return virtualHost.determineDispatcher(request.getPath());
  }

  void queueRequest(Runnable runnable) {
    executor.execute(runnable);
  }

  private InternalVirtualHost findVirtualHost(String hostName) {
    if (hostName == null) {
      return defaultDomain;
    }
    if (aliases.containsKey(hostName)) {
      hostName = aliases.get(hostName);
    }
    InternalVirtualHost virtualHost = hosts.get(hostName);
    return virtualHost == null ? defaultDomain : virtualHost;
  }

  public void saveSessions(OutputStream target) throws IOException {
    sessionManager.save(target);
  }

  public void loadSessions(InputStream target) throws IOException {
    sessionManager.load(target);
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

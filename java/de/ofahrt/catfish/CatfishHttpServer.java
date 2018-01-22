package de.ofahrt.catfish;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.servlet.http.HttpServletResponse;

import de.ofahrt.catfish.utils.HttpFieldName;
import de.ofahrt.catfish.utils.HttpResponseCode;

/**
 * A <code>CatfishHttpServer</code> manages a HTTP-Server.
 */
public final class CatfishHttpServer {

  public enum EventType {
    OPEN_CONNECTION, CLOSE_CONNECTION,
    RECV_START, RECV_END,
    SERVLET_START, SERVLET_END,
    WRITE_START, WRITE_END;
  }

  public interface ServerListener {
    void openPort(int port, boolean ssl);
    void shutdown();
    void event(ConnectionId id, EventType event);
    void notifyException(ConnectionId id, Throwable throwable);
    void notifyBadRequest(ConnectionId id, Throwable throwable);
    void notifyBadRequest(ConnectionId id, String msg);
  }

  interface RequestCallback extends Runnable {
    void reject();
  }

  private final ServerListener serverListener;

  private volatile InternalVirtualHost defaultDomain;

  private final ConcurrentHashMap<String, InternalVirtualHost> hosts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> aliases = new ConcurrentHashMap<>();

  private volatile boolean mayCompress = true;
  private volatile boolean mayKeepAlive = false;
  private volatile String defaultCharset = "UTF-8";

  private final ArrayList<RequestListener> listeners = new ArrayList<>();
  private final NioEngine engine;

  private final SessionManager sessionManager = new SessionManager();

  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(4, 4, 1L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(50));

  public CatfishHttpServer(ServerListener serverListener) throws IOException {
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

  ServerListener getServerListener() {
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

  public void addVirtualHost(String name, VirtualHost virtualHost) {
    if (defaultDomain == null) {
      defaultDomain = virtualHost;
    }
    hosts.put(name, virtualHost);
  }

  public void addAlias(String alias, String name) {
    aliases.put(alias, name);
  }

  public void setDefaultCharset(String defaultCharset) {
    this.defaultCharset = defaultCharset;
  }

  public void setCompressionAllowed(boolean how) {
    mayCompress = how;
  }

  public void setKeepAliveAllowed(boolean how) {
    mayKeepAlive = how;
  }

  public void addRequestListener(RequestListener l) {
    listeners.add(l);
  }

  public void removeRequestListener(RequestListener l) {
    listeners.remove(l);
  }

  public String getServerName() {
    return "Catfish/10.0";
  }

  void notifySent(RequestImpl request, ResponseImpl response, int amount) {
    for (int i = 0; i < listeners.size(); i++) {
      RequestListener l = listeners.get(i);
      try {
        l.notifySent(request, response, amount);
      } catch (Throwable error) {
        error.printStackTrace();
      }
    }
  }

  void notifyInternalError(RequestImpl req, Throwable e) {
    e.printStackTrace();
    for (int i = 0; i < listeners.size(); i++) {
      RequestListener l = listeners.get(i);
      try {
        l.notifyInternalError(req, e);
      } catch (Throwable error) {
        error.printStackTrace();
      }
    }
  }

  SSLContext getSSLContext(String host) {
    InternalVirtualHost domain = findVirtualHost(host);
    return domain == null ? defaultDomain.getSSLContext() : domain.getSSLContext();
  }

  ResponseImpl createErrorResponse(RequestImpl request) {
    return createErrorResponse(request.getErrorCode(), request.getError());
  }

  ResponseImpl createErrorResponse(int statusCode) {
    return createErrorResponse(statusCode, HttpResponseCode.getStatusText(statusCode));
  }

  ResponseImpl createErrorResponse(int statusCode, String message) {
    ResponseImpl response = new ResponseImpl();
    response.setCharacterEncoding(defaultCharset);
    response.setVersion(1, 1);
    response.setHeader(HttpFieldName.SERVER, getServerName());
    response.setHeader(HttpFieldName.DATE, CoreHelper.formatDate(System.currentTimeMillis()));
    response.setStatus(statusCode);
    response.setContentType(CoreHelper.MIME_TEXT_PLAIN);
    response.setBodyString(message);
    response.close();
    return response;
  }

  ResponseImpl createResponse(RequestImpl request) {
    request.setSessionManager(sessionManager);
    ResponseImpl response = request.getResponse();
    if ("HEAD".equals(request.getMethod())) {
      response.setHeadRequest();
    }
    response.setCharacterEncoding(defaultCharset);
    response.setVersion(1, 1);
    response.setHeader(HttpFieldName.SERVER, getServerName());
    response.setHeader(HttpFieldName.DATE, CoreHelper.formatDate(System.currentTimeMillis()));

    if (mayCompress) {
      response.setCompressionAllowed(request.supportGzipCompression());
    }
    if (mayKeepAlive && request.mayKeepAlive()) {
      response.setHeader(HttpFieldName.CONNECTION, "keep-alive");
    }

    if ((request.getMajorVersion() == 1) && (request.getMinorVersion() == 1) &&
        (request.getHeader(HttpFieldName.HOST) == null)) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST);
    } else if (request.getHeader(HttpFieldName.EXPECT) != null) {
      response.sendError(HttpServletResponse.SC_EXPECTATION_FAILED);
    } else if ("*".equals(request.getUnparsedUri())) {
      response.setStatus(HttpServletResponse.SC_OK);
    } else {
      try {
        FilterDispatcher dispatcher = determineDispatcher(request);
        dispatcher.dispatch(request, response);
      } catch (Exception e) {
        if (e instanceof FileNotFoundException) {
          return createErrorResponse(HttpServletResponse.SC_NOT_FOUND);
        } else {
          notifyInternalError(request, e);
          return createErrorResponse(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
      }
    }
    response.close();
    return response;
  }

  void queueRequest(Runnable runnable) {
    executor.execute(runnable);
  }

  private FilterDispatcher determineDispatcher(RequestImpl request) {
    String host = request.getHeader(HttpFieldName.HOST);
    if (host != null) {
      if (host.indexOf(':') >= 0) {
        host = host.substring(0, host.indexOf(':'));
      }
    }
    InternalVirtualHost virtualHost = findVirtualHost(host);
    return virtualHost.determineDispatcher(request);
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

package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.Connection;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.NetworkEventListener;

public interface HttpServerListener extends NetworkEventListener {
  public static final HttpServerListener NULL = new HttpServerListener() {
    @Override
    public void portOpened(int port, boolean ssl) {
    }

    @Override
    public void shutdown() {
    }
  };

  /**
   * @param connection
   * @param request
   * @param response
   */
  @Deprecated
  default void notifyRequest(Connection connection, HttpRequest request, HttpResponse response) {
  }
}
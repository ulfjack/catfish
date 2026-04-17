package de.ofahrt.catfish.integration;

import de.ofahrt.catfish.RawHttpConnection;
import de.ofahrt.catfish.model.HttpResponse;
import java.io.IOException;

interface Server {

  void setStartSsl(boolean startSsl);

  void start() throws Exception;

  void shutdown() throws Exception;

  HttpResponse send(byte[] content) throws IOException;

  HttpResponse send(String content) throws IOException;

  HttpResponse sendSsl(String content) throws IOException;

  RawHttpConnection connect(boolean ssl) throws IOException;

  void waitForNoOpenConnections();
}

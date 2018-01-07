package de.ofahrt.catfish.integration;

import java.io.IOException;

import de.ofahrt.catfish.client.HttpConnection;
import de.ofahrt.catfish.client.HttpResponse;

interface Server {

  void setStartSsl(boolean startSsl);

  void start() throws Exception;

  void shutdown() throws Exception;

  HttpResponse send(String content) throws IOException;

  HttpResponse sendSsl(String content) throws IOException;

  HttpResponse sendSslWithSni(String sniHostname, String content) throws IOException;

  HttpConnection connect(boolean ssl) throws IOException;

  void waitForNoOpenConnections();
}

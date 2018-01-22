package de.ofahrt.catfish;

import javax.net.ssl.SSLContext;

interface InternalVirtualHost {
  SSLContext getSSLContext();
  FilterDispatcher determineDispatcher(RequestImpl request);
}
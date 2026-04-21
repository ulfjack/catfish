package de.ofahrt.catfish.integration;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  BasicIntegrationTest.class,
  ConnectTunnelIntegrationTest.class,
  UnixSocketIntegrationTest.class,
  MitmConnectIntegrationTest.class,
  ChunkedBodyIntegrationTest.class,
  ConnectionHandlingTest.class,
  MultiRunnerTest.class,
  CompressionIntegrationTest.class,
  HttpParserIntegrationTest.class,
  SslHttpParserIntegrationTest.class,
  CatfishHttpClientIntegrationTest.class,
  StatefulClientIntegrationTest.class,
  HttpResponseValidationIntegrationTest.class,
  MixedServerIntegrationTest.class,
  HttpServerListenerIntegrationTest.class,
})
public class IntegrationTestSuite {
  // Just a test suite; no methods.
}

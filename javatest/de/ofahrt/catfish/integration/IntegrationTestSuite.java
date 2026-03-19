package de.ofahrt.catfish.integration;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  BasicIntegrationTest.class,
  ChunkedBodyIntegrationTest.class,
  ConnectionHandlingTest.class,
  MultiRunnerTest.class,
  CompressionIntegrationTest.class,
  HttpParserIntegrationTest.class,
  SslHttpParserIntegrationTest.class,
  CatfishHttpClientIntegrationTest.class,
  StatefulClientIntegrationTest.class,
})
public class IntegrationTestSuite {
  // Just a test suite; no methods.
}

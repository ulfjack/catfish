package de.ofahrt.catfish.integration;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  ConnectionHandlingTest.class,
  MultiRunnerTest.class,
  BasicIntegrationTest.class,
  HttpParserIntegrationTest.class,
  SslHttpParserIntegrationTest.class,
})
public class IntegrationTestSuite {
// Just a test suite; no methods.
}

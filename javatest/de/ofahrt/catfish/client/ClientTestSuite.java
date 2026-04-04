package de.ofahrt.catfish.client;

import de.ofahrt.catfish.client.legacy.LegacyIncrementalHttpResponseParserTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  ClientStageTest.class,
  LegacyIncrementalHttpResponseParserTest.class,
  HttpConnectionTest.class,
  HttpRequestGeneratorBufferedTest.class,
  IncrementalHttpResponseParserTest.class,
  IncrementalHttpResponseParserIncrementalTest.class,
  LoggingNetworkEventListenerTest.class,
  LoggingTrustManagerTest.class,
  SslHelperTest.class,
})
public class ClientTestSuite {
  // Ok
}

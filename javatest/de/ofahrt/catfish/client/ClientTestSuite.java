package de.ofahrt.catfish.client;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  ClientStageTest.class,
  RawHttpConnectionTest.class,
  HttpRequestGeneratorBufferedTest.class,
  IncrementalHttpResponseParserTest.class,
  IncrementalHttpResponseParserIncrementalTest.class,
  LoggingNetworkEventListenerTest.class,
  LoggingTrustManagerTest.class,
  SslHelperTest.class,
})
public class ClientTestSuite {}

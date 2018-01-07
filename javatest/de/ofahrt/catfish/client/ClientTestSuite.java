package de.ofahrt.catfish.client;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HttpConnectionTest.class,
  IncrementalHttpResponseParserTest.class,
  IncrementalHttpResponseParserIncrementalTest.class,
})
public class ClientTestSuite {
// Ok
}

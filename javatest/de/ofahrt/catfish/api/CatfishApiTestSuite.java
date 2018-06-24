package de.ofahrt.catfish.api;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HttpHeaderNameTest.class,
  HttpHeadersTest.class,
  HttpResponseCodeTest.class,
  HttpVersionTest.class,
  SimpleHttpRequestTest.class,
})
public class CatfishApiTestSuite {
}

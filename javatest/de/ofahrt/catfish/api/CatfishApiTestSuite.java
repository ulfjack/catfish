package de.ofahrt.catfish.api;

import de.ofahrt.catfish.model.HttpResponseValidatorTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HttpHeaderNameTest.class,
  HttpHeadersTest.class,
  HttpResponseCodeTest.class,
  HttpResponseValidatorTest.class,
  HttpVersionTest.class,
  SimpleHttpRequestTest.class,
})
public class CatfishApiTestSuite {}

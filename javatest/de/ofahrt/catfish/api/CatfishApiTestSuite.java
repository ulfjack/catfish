package de.ofahrt.catfish.api;

import de.ofahrt.catfish.model.HttpResponseValidatorTest;
import de.ofahrt.catfish.model.PreconditionsTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HttpHeaderNameTest.class,
  HttpHeadersTest.class,
  HttpResponseCodeTest.class,
  HttpStatusCodeTest.class,
  HttpResponseValidatorTest.class,
  HttpVersionTest.class,
  PreconditionsTest.class,
  SimpleHttpRequestTest.class,
})
public class CatfishApiTestSuite {}

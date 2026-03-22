package de.ofahrt.catfish.api;

import de.ofahrt.catfish.model.HttpResponseValidatorTest;
import de.ofahrt.catfish.model.InternalServerErrorResponseTest;
import de.ofahrt.catfish.model.PreconditionsTest;
import de.ofahrt.catfish.model.RedirectResponseTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HttpHeaderNameTest.class,
  HttpHeadersTest.class,
  HttpResponseValidatorTest.class,
  HttpStatusCodeTest.class,
  HttpVersionTest.class,
  InternalServerErrorResponseTest.class,
  PreconditionsTest.class,
  RedirectResponseTest.class,
  SimpleHttpRequestTest.class,
  StandardResponsesTest.class,
})
public class CatfishApiTestSuite {}

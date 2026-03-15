package de.ofahrt.catfish.model;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HttpRequestTest.class,
  HttpResponseValidatorTest.class,
  InternalServerErrorResponseTest.class,
  PreconditionsTest.class,
  RedirectResponseTest.class,
})
public class ModelTestSuite {}

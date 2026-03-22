package de.ofahrt.catfish.model;

import de.ofahrt.catfish.model.network.ModelNetworkTestSuite;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HttpDateTest.class,
  HttpRequestTest.class,
  HttpResponseValidatorTest.class,
  InternalServerErrorResponseTest.class,
  PreconditionsTest.class,
  RedirectResponseTest.class,
  ModelNetworkTestSuite.class,
})
public class ModelTestSuite {}

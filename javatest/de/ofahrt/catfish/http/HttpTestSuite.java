package de.ofahrt.catfish.http;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  ContinueResponseGeneratorTest.class,
  HttpResponseGeneratorBufferedTest.class,
  HttpResponseGeneratorStreamedTest.class,
})
public class HttpTestSuite {}

package de.ofahrt.catfish.utils;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HttpAcceptEncodingTest.class,
  HttpCacheControlTest.class,
  HttpConnectionHeaderTest.class,
  HttpContentTypeTest.class,
  MimeTypeRegistryTest.class,
  MimeTypeTest.class,
})
public class CatfishUtilsTestSuite {}

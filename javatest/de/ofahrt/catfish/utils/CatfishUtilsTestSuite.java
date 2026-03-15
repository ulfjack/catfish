package de.ofahrt.catfish.utils;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HttpCacheControlTest.class,
  HttpContentTypeTest.class,
  HttpDateTest.class,
  MimeTypeRegistryTest.class,
})
public class CatfishUtilsTestSuite {}

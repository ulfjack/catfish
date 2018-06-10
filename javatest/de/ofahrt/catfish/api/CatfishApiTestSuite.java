package de.ofahrt.catfish.api;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HttpHeadersTest.class,
  HttpVersionTest.class,
})
public class CatfishApiTestSuite {
}

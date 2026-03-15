package de.ofahrt.catfish.bridge;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  FileDataTest.class,
  HttpFilterTest.class,
  RequestImplTest.class,
  ServletHelperTest.class,
  SessionManagerTest.class,
  XsrfTokenTest.class,
})
public class CatfishBridgeTestSuite {}

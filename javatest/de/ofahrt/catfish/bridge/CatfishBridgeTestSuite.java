package de.ofahrt.catfish.bridge;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  EnumerationsTest.class,
  FileDataTest.class,
  FilterDispatcherTest.class,
  HttpFilterTest.class,
  RequestImplTest.class,
  ResponseImplTest.class,
  ServletHelperTest.class,
  ServletHttpHandlerTest.class,
  SessionManagerTest.class,
  XsrfTokenTest.class,
})
public class CatfishBridgeTestSuite {}

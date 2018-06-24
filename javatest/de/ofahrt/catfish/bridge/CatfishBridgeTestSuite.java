package de.ofahrt.catfish.bridge;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  ServletHelperTest.class,
  XsrfTokenTest.class,
  RequestImplTest.class,
  SessionManagerTest.class,
})
public class CatfishBridgeTestSuite {
}

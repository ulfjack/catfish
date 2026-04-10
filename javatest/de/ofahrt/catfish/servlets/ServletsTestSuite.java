package de.ofahrt.catfish.servlets;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  CheckPostTest.class,
  DirectoryServletTest.class,
  RedirectServletTest.class,
})
public class ServletsTestSuite {
  // Ok
}

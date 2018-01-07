package de.ofahrt.catfish.servlets;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

import de.ofahrt.catfish.servlets.CheckCompressionTest;

@RunWith(Suite.class)
@SuiteClasses({
  CheckCompressionTest.class,
})
public class ServletsTestSuite {
// Ok
}

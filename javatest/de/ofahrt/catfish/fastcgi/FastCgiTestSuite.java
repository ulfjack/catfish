package de.ofahrt.catfish.fastcgi;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  FcgiHandlerTest.class,
  IncrementalFcgiResponseParserTest.class,
})
public class FastCgiTestSuite {}

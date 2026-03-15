package de.ofahrt.catfish.upload;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  FormEntryTest.class,
  IncrementalMultipartParserTest.class,
  UrlEncodedParserTest.class,
})
public class CatfishUploadTestSuite {}

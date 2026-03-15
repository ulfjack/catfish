package de.ofahrt.catfish.upload;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  FormDataBodyTest.class,
  FormEntryTest.class,
  IncrementalMultipartParserTest.class,
  SimpleUploadPolicyTest.class,
  UrlEncodedParserTest.class,
})
public class CatfishUploadTestSuite {}

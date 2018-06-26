package de.ofahrt.catfish;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

import de.ofahrt.catfish.api.CatfishApiTestSuite;
import de.ofahrt.catfish.bridge.CatfishBridgeTestSuite;
import de.ofahrt.catfish.client.ClientTestSuite;
import de.ofahrt.catfish.servlets.ServletsTestSuite;
import de.ofahrt.catfish.utils.CatfishUtilsTestSuite;

@RunWith(Suite.class)
@SuiteClasses({
  CatfishHttpServerTest.class,
  CoreHelperTest.class,
  HashConflictGeneratorTest.class,
  IncrementalHttpParserIncrementalTest.class,
  IncrementalHttpParserTest.class,
  HttpResponseGeneratorBufferedTest.class,
  HttpResponseGeneratorStreamedTest.class,
  MatchMapTest.class,
  MimeMultipartParserTest.class,
  PathFragmentIteratorTest.class,
  PathTrackerTest.class,
  SiteLayoutTest.class,
  SNIParserTest.class,
  VirtualHostTest.class,

  CatfishApiTestSuite.class,
  CatfishBridgeTestSuite.class,
  CatfishUtilsTestSuite.class,
  ServletsTestSuite.class,
  ClientTestSuite.class,
})
public class CatfishTestSuite {
}

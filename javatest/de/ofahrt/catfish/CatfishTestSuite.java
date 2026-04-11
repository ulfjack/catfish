package de.ofahrt.catfish;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  CatfishHttpServerTest.class,
  ChunkedBodyScannerTest.class,
  ContinueResponseGeneratorTest.class,
  HashConflictGeneratorTest.class,
  HttpVirtualHostTest.class,
  IncrementalHttpParserIncrementalTest.class,
  IncrementalHttpParserTest.class,
  ValidatingHttpHandlerTest.class,
  HttpResponseGeneratorBufferedTest.class,
  HttpResponseGeneratorStreamedTest.class,
  PipeBufferTest.class,
  SNIParserTest.class,
  SslInfoCacheTest.class,
  SslServerStageTest.class,
  TunnelForwardStageTest.class,
})
public class CatfishTestSuite {}

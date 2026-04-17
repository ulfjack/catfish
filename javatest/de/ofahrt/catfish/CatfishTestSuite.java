package de.ofahrt.catfish;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  CatfishHttpServerTest.class,
  ChunkedDecodingOutputStreamTest.class,
  ChunkedBodyScannerTest.class,
  ContinueResponseGeneratorTest.class,
  HashConflictGeneratorTest.class,
  Http2EndpointTest.class,
  Http2HandlerTest.class,
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
  VirtualHostRouterTest.class,
})
public class CatfishTestSuite {}

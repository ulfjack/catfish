package de.ofahrt.catfish;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  AsyncRoutingDispatcherTest.class,
  CatfishHttpServerTest.class,
  ConnectStageTest.class,
  HashConflictGeneratorTest.class,
  Http2EndpointTest.class,
  Http2HandlerTest.class,
  HttpEndpointTest.class,
  HttpServerStageTest.class,
  HttpsEndpointTest.class,
  HttpVirtualHostTest.class,
  OriginForwarderTest.class,
  ValidatingHttpHandlerTest.class,
  PipeBufferTest.class,
  ProxyRequestStageTest.class,
  SNIParserTest.class,
  SslInfoCacheTest.class,
  SslServerStageTest.class,
  TunnelForwardStageTest.class,
  VirtualHostRouterTest.class,
})
public class CatfishTestSuite {}

package de.ofahrt.catfish;

import de.ofahrt.catfish.api.CatfishApiTestSuite;
import de.ofahrt.catfish.bridge.CatfishBridgeTestSuite;
import de.ofahrt.catfish.client.ClientTestSuite;
import de.ofahrt.catfish.fastcgi.FastCgiTestSuite;
import de.ofahrt.catfish.integration.IntegrationTestSuite;
import de.ofahrt.catfish.internal.CatfishInternalTestSuite;
import de.ofahrt.catfish.model.ModelTestSuite;
import de.ofahrt.catfish.model.layout.ModelLayoutTestSuite;
import de.ofahrt.catfish.model.server.ModelServerTestSuite;
import de.ofahrt.catfish.servlets.ServletsTestSuite;
import de.ofahrt.catfish.ssl.CatfishSslTestSuite;
import de.ofahrt.catfish.upload.CatfishUploadTestSuite;
import de.ofahrt.catfish.utils.CatfishUtilsTestSuite;
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
  FastCgiTestSuite.class,
  CatfishInternalTestSuite.class,
  CatfishSslTestSuite.class,
  CatfishApiTestSuite.class,
  CatfishBridgeTestSuite.class,
  CatfishUploadTestSuite.class,
  CatfishUtilsTestSuite.class,
  ModelTestSuite.class,
  ModelLayoutTestSuite.class,
  ModelServerTestSuite.class,
  ServletsTestSuite.class,
  ClientTestSuite.class,
  IntegrationTestSuite.class,
})
public class CatfishTestSuite {}

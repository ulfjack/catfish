package de.ofahrt.catfish.http;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  ChunkedBodyScannerTest.class,
  ChunkedDecodingOutputStreamTest.class,
  HttpResponseGeneratorBufferedTest.class,
  HttpResponseGeneratorStreamedTest.class,
  IncrementalHttpParserIncrementalTest.class,
  IncrementalHttpParserTest.class,
})
public class HttpTestSuite {}

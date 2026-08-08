package de.ofahrt.catfish.http;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  ChunkedBodyScannerTest.class,
  ChunkedBodyStateTest.class,
  ChunkedDecodingOutputStreamTest.class,
  CompressingResponseWriterTest.class,
  GzipRequestBodyDecoderTest.class,
  HttpResponseGeneratorBufferedTest.class,
  HttpResponseGeneratorStreamedTest.class,
  IncrementalHttpParserIncrementalTest.class,
  IncrementalHttpParserTest.class,
})
public class HttpTestSuite {}

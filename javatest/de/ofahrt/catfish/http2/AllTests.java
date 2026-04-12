package de.ofahrt.catfish.http2;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HpackTest.class,
  Http2FrameReaderTest.class,
  Http2FrameWriterTest.class,
})
public class AllTests {}

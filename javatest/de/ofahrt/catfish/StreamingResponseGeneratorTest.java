package de.ofahrt.catfish;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.Servlet;

import org.junit.Test;

import de.ofahrt.catfish.api.HttpResponse;

public class StreamingResponseGeneratorTest {

  @Test
  public void smoke() throws Exception {
    AtomicBoolean callbackCalled = new AtomicBoolean();
    StreamingResponseGenerator gen = new StreamingResponseGenerator(
        HttpResponse.OK, () -> {
          if (!callbackCalled.compareAndSet(false, true)) {
            throw new IllegalStateException();
          }
        });
    OutputStream out = gen.getOutputStream();
    assertTrue(callbackCalled.getAndSet(false));
    out.write(new byte[] { 'x', 'y' });
  }
}

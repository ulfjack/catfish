package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class MultiRunnerTest {

  @Test
  public void simple() throws Exception {
    MultiRunner runner = new MultiRunner();
    final AtomicBoolean test = new AtomicBoolean();
    runner.add(new Runnable() {
      @Override
      public void run() {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        test.set(true);
      }
    });
    assertFalse(test.get());
    runner.runAll();
    assertTrue(test.get());
  }
}
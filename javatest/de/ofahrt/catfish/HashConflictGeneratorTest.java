package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class HashConflictGeneratorTest {

  private static final String ALPHA_NUMERIC =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

  @Test
  public void generateList() {
    HashConflictGenerator generator = HashConflictGenerator.using(ALPHA_NUMERIC).withTarget("rr");
    assertEquals(Arrays.asList("rr", "sS", "t4"), generator.generateList(-1));
  }

  @Test
  public void generateListWithMaxCount() {
    HashConflictGenerator generator = HashConflictGenerator.using(ALPHA_NUMERIC).withTarget("rr");
    assertEquals(Arrays.asList("rr", "sS"), generator.generateList(2));
  }
}

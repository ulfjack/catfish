package de.ofahrt.catfish.client;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;

public class SslHelperTest {

  @Test
  public void filter_emptyInput() {
    assertArrayEquals(new String[0], SslHelper.filter(new String[0], new HashSet<>()));
  }

  @Test
  public void filter_allMatch() {
    String[] input = {"a", "b"};
    assertArrayEquals(input, SslHelper.filter(input, new HashSet<>(Arrays.asList("a", "b"))));
  }

  @Test
  public void filter_someMatch() {
    assertArrayEquals(
        new String[] {"b"},
        SslHelper.filter(new String[] {"a", "b", "c"}, new HashSet<>(Arrays.asList("b"))));
  }

  @Test
  public void filter_noneMatch() {
    assertArrayEquals(
        new String[0],
        SslHelper.filter(new String[] {"a", "b"}, new HashSet<>(Arrays.asList("x"))));
  }
}

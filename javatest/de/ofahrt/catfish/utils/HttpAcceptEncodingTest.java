package de.ofahrt.catfish.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class HttpAcceptEncodingTest {

  @Test
  public void gzip_acceptable() {
    assertTrue(HttpAcceptEncoding.parse("gzip").isAcceptable("gzip"));
  }

  @Test
  public void gzip_q1_acceptable() {
    assertTrue(HttpAcceptEncoding.parse("gzip;q=1").isAcceptable("gzip"));
  }

  @Test
  public void gzip_q05_acceptable() {
    HttpAcceptEncoding ae = HttpAcceptEncoding.parse("gzip;q=0.5");
    assertTrue(ae.isAcceptable("gzip"));
    assertEquals(0.5, ae.getQValue("gzip"), 1e-9);
  }

  @Test
  public void gzip_q0_notAcceptable() {
    assertFalse(HttpAcceptEncoding.parse("gzip;q=0").isAcceptable("gzip"));
  }

  @Test
  public void deflate_gzipNotAcceptable() {
    HttpAcceptEncoding ae = HttpAcceptEncoding.parse("deflate");
    assertFalse(ae.isAcceptable("gzip"));
    assertTrue(ae.isAcceptable("deflate"));
  }

  @Test
  public void deflateAndGzip_bothAcceptable() {
    HttpAcceptEncoding ae = HttpAcceptEncoding.parse("deflate, gzip");
    assertTrue(ae.isAcceptable("gzip"));
    assertTrue(ae.isAcceptable("deflate"));
  }

  @Test
  public void wildcard_gzipAcceptable() {
    assertTrue(HttpAcceptEncoding.parse("*").isAcceptable("gzip"));
  }

  @Test
  public void wildcard_q0_nothingAcceptable() {
    HttpAcceptEncoding ae = HttpAcceptEncoding.parse("*;q=0");
    assertFalse(ae.isAcceptable("gzip"));
    assertFalse(ae.isAcceptable("deflate"));
  }

  @Test
  public void wildcardWithGzipQ0_gzipNotAcceptable() {
    // Explicit entry overrides wildcard
    HttpAcceptEncoding ae = HttpAcceptEncoding.parse("*, gzip;q=0");
    assertFalse(ae.isAcceptable("gzip"));
    assertTrue(ae.isAcceptable("deflate"));
  }

  @Test
  public void wildcardQ0WithGzip_gzipAcceptable() {
    // Explicit entry overrides wildcard
    HttpAcceptEncoding ae = HttpAcceptEncoding.parse("*;q=0, gzip");
    assertTrue(ae.isAcceptable("gzip"));
    assertFalse(ae.isAcceptable("deflate"));
  }

  @Test
  public void recommend_higherQWins() {
    Optional<String> result =
        HttpAcceptEncoding.parse("gzip;q=0.5, deflate;q=0.8").recommend(List.of("gzip", "deflate"));
    assertEquals(Optional.of("deflate"), result);
  }

  @Test
  public void recommend_tieBreakByServerPreference() {
    Optional<String> result =
        HttpAcceptEncoding.parse("gzip;q=0.8, deflate;q=0.8").recommend(List.of("gzip", "deflate"));
    assertEquals(Optional.of("gzip"), result);
  }

  @Test
  public void recommend_q0_empty() {
    Optional<String> result = HttpAcceptEncoding.parse("gzip;q=0").recommend(List.of("gzip"));
    assertEquals(Optional.empty(), result);
  }

  @Test
  public void recommend_wildcard_serverPreference() {
    Optional<String> result = HttpAcceptEncoding.parse("*").recommend(List.of("lz4", "deflate"));
    assertEquals(Optional.of("lz4"), result);
  }

  @Test
  public void malformedQValue_tokenSkipped() {
    assertFalse(HttpAcceptEncoding.parse("gzip;q=abc").isAcceptable("gzip"));
  }

  @Test
  public void emptyToken_ignored() {
    // trailing comma produces an empty token that should be skipped
    assertTrue(HttpAcceptEncoding.parse("gzip, ").isAcceptable("gzip"));
  }
}

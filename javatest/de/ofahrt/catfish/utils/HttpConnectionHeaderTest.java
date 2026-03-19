package de.ofahrt.catfish.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import java.util.Map;
import org.junit.Test;

public class HttpConnectionHeaderTest {

  @Test
  public void keepAliveByDefault() {
    HttpHeaders headers = HttpHeaders.of(Map.of());
    assertTrue(HttpConnectionHeader.isKeepAlive(headers));
  }

  @Test
  public void closeLowercase() {
    HttpHeaders headers = HttpHeaders.of(Map.of(HttpHeaderName.CONNECTION, "close"));
    assertFalse(HttpConnectionHeader.isKeepAlive(headers));
  }

  @Test
  public void closeUppercase() {
    HttpHeaders headers = HttpHeaders.of(Map.of(HttpHeaderName.CONNECTION, "Close"));
    assertFalse(HttpConnectionHeader.isKeepAlive(headers));
  }

  @Test
  public void closeAllCaps() {
    HttpHeaders headers = HttpHeaders.of(Map.of(HttpHeaderName.CONNECTION, "CLOSE"));
    assertFalse(HttpConnectionHeader.isKeepAlive(headers));
  }
}

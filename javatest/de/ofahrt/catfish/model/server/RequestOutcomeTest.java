package de.ofahrt.catfish.model.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import java.io.IOException;
import org.junit.Test;

public class RequestOutcomeTest {

  @Test
  public void success_setsResponseAndBytesSent_noError() {
    HttpResponse response = StandardResponses.OK;
    RequestOutcome outcome = RequestOutcome.success(response, 42);
    assertTrue(outcome.isSuccess());
    assertSame(response, outcome.response());
    assertEquals(42L, outcome.bytesSent());
    assertNull(outcome.error());
  }

  @Test
  public void errorOnly_setsErrorAndZeroBytes_noResponse() {
    IOException cause = new IOException("boom");
    RequestOutcome outcome = RequestOutcome.error(cause);
    assertFalse(outcome.isSuccess());
    assertNull(outcome.response());
    assertSame(cause, outcome.error());
    assertEquals(0L, outcome.bytesSent());
  }

  @Test
  public void errorWithResponse_setsAll() {
    HttpResponse partial = StandardResponses.OK;
    IOException cause = new IOException("mid-stream disconnect");
    RequestOutcome outcome = RequestOutcome.error(partial, cause, 123);
    assertFalse(outcome.isSuccess());
    assertSame(partial, outcome.response());
    assertSame(cause, outcome.error());
    assertEquals(123L, outcome.bytesSent());
  }
}

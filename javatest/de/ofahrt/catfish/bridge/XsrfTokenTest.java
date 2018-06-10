package de.ofahrt.catfish.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import javax.servlet.http.HttpSession;

import org.junit.Test;

import de.ofahrt.catfish.TestHelper;
import de.ofahrt.catfish.bridge.XsrfToken;

public class XsrfTokenTest {

  @Test
  public void smoke() {
    HttpSession session = TestHelper.createSessionForTesting();
    String token = XsrfToken.getToken(session);
    assertNotNull(token);
    assertTrue(token.length() >= 16);
    assertTrue(XsrfToken.isValid(session, token));
    assertFalse(XsrfToken.isValid(session, token + "x"));
  }

  @Test
  public void nullIsntValid() {
    assertFalse(XsrfToken.isValid(null, "abc"));
    assertFalse(XsrfToken.isValid(TestHelper.createSessionForTesting(), null));
  }

  @Test
  public void cannotUseTokenForAnotherSession() {
    HttpSession sessionA = TestHelper.createSessionForTesting();
    HttpSession sessionB = TestHelper.createSessionForTesting();
    String tokenA = XsrfToken.getToken(sessionA);
    String tokenB = XsrfToken.getToken(sessionB);
    assertNotNull(tokenA);
    assertNotNull(tokenB);
    assertFalse(XsrfToken.isValid(sessionA, tokenB));
    assertFalse(XsrfToken.isValid(sessionB, tokenA));
  }
}

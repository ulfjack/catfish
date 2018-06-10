package de.ofahrt.catfish.bridge;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpSession;

public final class XsrfToken {
  private static final String TOKEN_KEY = "xsrf-token";

  public static String getToken(HttpSession session) {
    if (session == null) {
      throw new NullPointerException();
    }
    AtomicReference<String> result = cast(session.getAttribute(TOKEN_KEY));
    if (result == null) {
      session.setAttribute(TOKEN_KEY, new AtomicReference<String>());
      result = cast(session.getAttribute(TOKEN_KEY));
    }
    if (result.get() == null) {
      result.compareAndSet(null, UUID.randomUUID().toString());
    }
    return result.get();
  }

  public static boolean isValid(HttpSession session, String token) {
    if ((session == null) || (token == null)) {
      return false;
    }
    AtomicReference<String> result = cast(session.getAttribute(TOKEN_KEY));
    if (result == null) {
      return false;
    }
    return token.equals(result.get());
  }

  @SuppressWarnings("unchecked")
  private static AtomicReference<String> cast(Object o) {
    return (AtomicReference<String>) o;
  }
}

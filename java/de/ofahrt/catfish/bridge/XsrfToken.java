package de.ofahrt.catfish.bridge;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.http.HttpSession;

public final class XsrfToken {
  private static final String TOKEN_KEY = "xsrf-token";

  public static String getToken(HttpSession session) {
    if (session == null) {
      throw new NullPointerException();
    }
    AtomicReference<String> ref = cast(session.getAttribute(TOKEN_KEY));
    if (ref == null) {
      session.setAttribute(TOKEN_KEY, new AtomicReference<String>());
      ref = cast(session.getAttribute(TOKEN_KEY));
    }
    String token = ref.get();
    if (token != null) {
      return token;
    }
    String newToken = UUID.randomUUID().toString();
    ref.compareAndSet(null, newToken);
    // Non-null: either our CAS succeeded, or another thread set a value first.
    return Objects.requireNonNull(ref.get(), "token");
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

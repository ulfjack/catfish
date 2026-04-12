package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpResponse;
import org.jspecify.annotations.Nullable;

public record RequestOutcome(
    @Nullable HttpResponse response, @Nullable Throwable error, long bytesSent) {

  public static RequestOutcome success(HttpResponse response, long bytesSent) {
    return new RequestOutcome(response, null, bytesSent);
  }

  public static RequestOutcome error(Throwable error) {
    return new RequestOutcome(null, error, 0);
  }

  public static RequestOutcome error(
      @Nullable HttpResponse response, Throwable error, long bytesSent) {
    return new RequestOutcome(response, error, bytesSent);
  }

  public boolean isSuccess() {
    return error == null;
  }
}

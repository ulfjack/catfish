package de.ofahrt.catfish;

public final class ConnectionId {

  private final String id;
  private final boolean secure;
  private final long startTimeNanos;

  public ConnectionId(String id, boolean secure, long startTimeNanos) {
    this.id = Preconditions.checkNotNull(id);
    this.secure = secure;
    this.startTimeNanos = startTimeNanos;
  }

  public String getId() {
    return id;
  }

  public boolean isSecure() {
    return secure;
  }

  public long getStartTimeNanos() {
    return startTimeNanos;
  }

  @Override
  public String toString() {
    return id + (secure ? " SSL" : "");
  }
}

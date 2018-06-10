package de.ofahrt.catfish;

import java.util.UUID;

public final class Connection {
  private final UUID id;

  public Connection() {
    this.id = UUID.randomUUID();
  }

  public UUID getId() {
    return id;
  }
}

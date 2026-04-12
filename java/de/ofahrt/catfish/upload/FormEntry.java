package de.ofahrt.catfish.upload;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class FormEntry {
  private final String name;
  private final @Nullable String value;
  private final @Nullable String contentType;
  private final byte @Nullable [] body;

  public FormEntry(String name, String value) {
    this.name = Objects.requireNonNull(name, "name");
    this.value = Objects.requireNonNull(value, "value");
    this.contentType = null;
    this.body = null;
  }

  public FormEntry(String name, String contentType, byte[] body) {
    this.name = Objects.requireNonNull(name, "name");
    this.value = null;
    this.contentType = Objects.requireNonNull(contentType, "contentType");
    this.body = Objects.requireNonNull(body, "body");
  }

  public String getName() {
    return name;
  }

  public boolean isFile() {
    return contentType != null;
  }

  public @Nullable String getValue() {
    return value;
  }

  public @Nullable String getContentType() {
    return contentType;
  }

  public byte @Nullable [] getBody() {
    return body;
  }
}

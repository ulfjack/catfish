package de.ofahrt.catfish.upload;

import org.jspecify.annotations.Nullable;

public final class FormEntry {
  private final String name;
  private final @Nullable String value;
  private final @Nullable String contentType;
  private final byte @Nullable [] body;

  public FormEntry(String name, String value) {
    if (name == null) {
      throw new NullPointerException();
    }
    if (value == null) {
      throw new NullPointerException();
    }
    this.name = name;
    this.value = value;
    this.contentType = null;
    this.body = null;
  }

  public FormEntry(String name, String contentType, byte[] body) {
    if (name == null) {
      throw new NullPointerException();
    }
    if (contentType == null) {
      throw new NullPointerException();
    }
    if (body == null) {
      throw new NullPointerException();
    }
    this.name = name;
    this.value = null;
    this.contentType = contentType;
    this.body = body;
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

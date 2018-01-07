package de.ofahrt.catfish.utils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public final class FileData {

  private final String id;
  private final String name;
  private final byte[] data;

  public FileData(String id, String name, byte[] data) {
    this.id = id;
    this.name = name;
    this.data = data;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public InputStream getInputStream() {
    return new ByteArrayInputStream(data);
  }
}

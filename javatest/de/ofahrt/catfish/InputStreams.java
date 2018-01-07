package de.ofahrt.catfish;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class InputStreams {

  public static byte[] toByteArray(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] data = new byte[1024];
    int length;
    while ((length = in.read(data)) != -1) {
      out.write(data, 0, length);
    }
    return out.toByteArray();
  }
}

package de.ofahrt.catfish.upload;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class UrlEncodedParser {

  public FormDataBody parse(byte[] input) {
    return parse(input, 0, input.length);
  }

  public FormDataBody parse(byte[] input, int offset, int length) {
    List<FormEntry> entries = new ArrayList<>();
    String inputAsString = new String(input, offset, length, StandardCharsets.UTF_8);
    for (String pair : inputAsString.split("&", -1)) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String name = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      entries.add(new FormEntry(name, value));
    }
    return new FormDataBody(entries);
  }
}

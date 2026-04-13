package de.ofahrt.catfish.upload;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlEncodedParser {
  private static final Pattern QUERY_PART_PATTERN = Pattern.compile("([^&=]*)=([^&]*)");

  public FormDataBody parse(byte[] input) {
    return parse(input, 0, input.length);
  }

  public FormDataBody parse(byte[] input, int offset, int length) {
    List<FormEntry> entries = new ArrayList<>();
    String inputAsString = new String(input, offset, length, StandardCharsets.UTF_8);
    Matcher mq = QUERY_PART_PATTERN.matcher(inputAsString);
    while (mq.find()) {
      String name = URLDecoder.decode(mq.group(1), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(mq.group(2), StandardCharsets.UTF_8);
      entries.add(new FormEntry(name, value));
    }
    return new FormDataBody(entries);
  }
}

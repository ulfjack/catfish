package de.ofahrt.catfish.upload;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlEncodedParser {
  private static final String queryPartStructure = "([^&=]*)=([^&]*)";
  private static final Pattern queryPartPattern = Pattern.compile(queryPartStructure);

  public FormDataBody parse(byte[] input) throws IOException {
    return parse(input, 0, input.length);
  }

  public FormDataBody parse(byte[] input, int offset, int length) throws IOException {
    List<FormEntry> entries = new ArrayList<>();
    String inputAsString = new String(input, offset, length, StandardCharsets.UTF_8);

    Matcher mq = queryPartPattern.matcher(inputAsString);
    while (mq.find()) {
      try {
        String name = URLDecoder.decode(mq.group(1), "UTF-8");
        String value = URLDecoder.decode(mq.group(2), "UTF-8");
        entries.add(new FormEntry(name, value));
      } catch (UnsupportedEncodingException e) {
        IOException e2 = new IOException("Internal Error!");
        e2.initCause(e);
        throw e2;
      }
    }
    return new FormDataBody(entries);
  }
}

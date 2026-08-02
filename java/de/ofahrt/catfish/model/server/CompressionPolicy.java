package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public interface CompressionPolicy {

  CompressionPolicy NONE = (request, mimeType) -> false;

  CompressionPolicy COMPRESS =
      new CompressionPolicy() {
        private static final Set<String> WHITELIST = buildWhitelist();

        private static Set<String> buildWhitelist() {
          HashSet<String> result = new HashSet<>();
          result.add("application/javascript");
          result.add("application/json");
          result.add("application/xhtml+xml");
          result.add("application/xml");
          result.add("application/xml-dtd");
          result.add("text/css");
          result.add("text/csv");
          result.add("text/html");
          result.add("text/javascript");
          result.add("text/plain");
          result.add("text/richtext");
          result.add("text/rtf");
          result.add("text/xml");
          return Collections.unmodifiableSet(result);
        }

        @Override
        public boolean shouldCompress(HttpRequest request, String mimeType) {
          return WHITELIST.contains(mimeType);
        }
      };

  /**
   * Returns whether a response of the given {@code mimeType} is worth compressing. This is a
   * content-type worthiness decision only; whether the client accepts a coding, and which one, is
   * negotiated separately by the response writer. {@code request} is available for policies that
   * gate on request attributes, though the built-in policies do not use it.
   */
  boolean shouldCompress(HttpRequest request, String mimeType);
}

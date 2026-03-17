package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public interface CompressionPolicy {

  CompressionPolicy NONE = (request, mimeType) -> false;

  CompressionPolicy COMPRESS =
      new CompressionPolicy() {
        private static final Set<String> WHITELIST = buildWhitelist();

        private static Set<String> buildWhitelist() {
          HashSet<String> result = new HashSet<>();
          result.add("application/javascript");
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
          if (!WHITELIST.contains(mimeType)) {
            return false;
          }
          String acceptEncoding = request.getHeaders().get(HttpHeaderName.ACCEPT_ENCODING);
          return acceptEncoding != null && acceptEncoding.toLowerCase(Locale.US).contains("gzip");
        }
      };

  boolean shouldCompress(HttpRequest request, String mimeType);
}

package de.ofahrt.catfish;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.utils.HttpConnectionHeader;

final class DefaultResponsePolicy implements ResponsePolicy {
  private static final String MIME_APPLICATION_JAVASCRIPT = "application/javascript";
  private static final String MIME_APPLICATION_XHTML_AND_XML = "application/xhtml+xml";
  private static final String MIME_APPLICATION_XML = "application/xml";
  private static final String MIME_APPLICATION_XML_DTD = "application/xml-dtd";

  private static final String MIME_TEXT_CSS = "text/css";
  private static final String MIME_TEXT_CSV = "text/csv";
  private static final String MIME_TEXT_HTML  = "text/html";
  private static final String MIME_TEXT_PLAIN = "text/plain";
  private static final String MIME_TEXT_RICHTEXT = "text/richtext";
  private static final String MIME_TEXT_RTF = "text/rtf";
  private static final String MIME_TEXT_XML = "text/xml";

  private static final Set<String> COMPRESSION_WHITELIST = constructCompressionWhitelist();

  private static Set<String> constructCompressionWhitelist() {
    HashSet<String> result = new HashSet<>();
    result.add(MIME_APPLICATION_JAVASCRIPT);
    result.add(MIME_APPLICATION_XHTML_AND_XML);
    result.add(MIME_APPLICATION_XML);
    result.add(MIME_APPLICATION_XML_DTD);

    result.add(MIME_TEXT_CSS);
    result.add(MIME_TEXT_CSV);
    result.add(MIME_TEXT_HTML);
    result.add(MIME_TEXT_PLAIN);
    result.add(MIME_TEXT_RICHTEXT);
    result.add(MIME_TEXT_RTF);
    result.add(MIME_TEXT_XML);
    return Collections.unmodifiableSet(result);
  }

  private final boolean mayKeepAlive;
  private final boolean mayCompress;

  DefaultResponsePolicy(boolean mayKeepAlive, boolean mayCompress) {
    this.mayKeepAlive = mayKeepAlive;
    this.mayCompress = mayCompress;
  }

  @Override
  public boolean shouldKeepAlive(HttpRequest request) {
    return mayKeepAlive && HttpConnectionHeader.mayKeepAlive(request);
  }

  @Override
  public boolean shouldCompress(HttpRequest request, String mimeType) {
    return mayCompress && COMPRESSION_WHITELIST.contains(mimeType) && supportGzipCompression(request);
  }

  private boolean supportGzipCompression(HttpRequest request) {
    String temp = request.getHeaders().get(HttpHeaderName.ACCEPT_ENCODING);
    if (temp != null) {
      if (temp.toLowerCase(Locale.US).indexOf("gzip") >= 0) {
        return true;
      }
    }
    // Some firewalls disable compression, but leave a header like this in place of the original one:
    // "~~~~~~~~~~~~~~~" -> "~~~~~ ~~~~~~~"
    // "---------------" -> "----- -------"
    // Norton sometimes eats the HTTP response if we compress anyway.
    return false;
  }
}
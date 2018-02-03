package de.ofahrt.catfish;

import java.io.PrintStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import de.ofahrt.catfish.utils.HttpDate;
import de.ofahrt.catfish.utils.HttpResponseCode;

final class CoreHelper {

  // Mime type support:
  private static final String MIME_APPLICATION_JAVASCRIPT = "application/javascript";
  private static final String MIME_APPLICATION_XHTML_AND_XML = "application/xhtml+xml";
  private static final String MIME_APPLICATION_XML = "application/xml";
  private static final String MIME_APPLICATION_XML_DTD = "application/xml-dtd";

  private static final String MIME_TEXT_CSS = "text/css";
  private static final String MIME_TEXT_CSV = "text/csv";
  static final String MIME_TEXT_HTML  = "text/html";
  static final String MIME_TEXT_PLAIN = "text/plain";
  private static final String MIME_TEXT_RICHTEXT = "text/richtext";
  private static final String MIME_TEXT_RTF = "text/rtf";
  private static final String MIME_TEXT_XML = "text/xml";

  public static final Set<String> COMPRESSION_WHITELIST = getCompressionSet();

  private static Set<String> getCompressionSet() {
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

  public static boolean shouldCompress(String mimeType) {
    return COMPRESSION_WHITELIST.contains(mimeType);
  }

  public static boolean isTextMimeType(String name) {
    return name.startsWith("text/");
  }

  public static final String formatDate(long date) {
    return HttpDate.formatDate(date);
  }

  public static final long parseDate(String date) {
    return HttpDate.parseDate(date);
  }

  // Response text output for debugging:
  public static final void printResponse(PrintStream out, ReadableHttpResponse response) {
    out.println(response.getProtocol() + " " + HttpResponseCode.getStatusText(response.getStatusCode()));
    Enumeration<String> it = response.getHeaderNames();
    while (it.hasMoreElements()) {
    	String key = it.nextElement();
    	out.println(key+": "+response.getHeader(key));
    }
    out.flush();
  }

  // Hex encoding:
  private static final String hexcodes = "0123456789ABCDEF";

  private static final String toHex(int i) {
    return "" + hexcodes.charAt((i >> 4) & 0xf) +
           hexcodes.charAt(i & 0xf);
  }

  public static final String encode(char c) {
    if (c <= 0x007F) {
      return "%"+toHex(c);
    }

    int i = c;
    int j = i & 0x3F; i = i >> 6;
    int k = i & 0x3F; i = i >> 6;
    int l = i;

    if (c <= 0x07FF) {
      return "%"+toHex(0xC0 + k)+"%"+toHex(0x80 + j);
    }
    return "%"+toHex(0xE0 + l)+"%"+toHex(0x80 + k)+"%"+toHex(0x80+j);
  }

  // Http version comparisons:
  public static int compareVersion(int major0, int minor0, int major1, int minor1) {
  	if (major0 > major1) return 1;
  	if (major0 < major1) return -1;
  	if (minor0 > minor1) return 1;
  	if (minor0 < minor1) return -1;
  	return 0;
  }

  private CoreHelper() {
    // Disallow instantiation.
  }
}

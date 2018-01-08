package de.ofahrt.catfish;

import java.io.PrintStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

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

  // Date parsing and formating:
  private static final Locale HTTP_LOCALE = new Locale("en", "us");

  // Sun, 06 Nov 1994 08:49:37 GMT
  private static DateFormat getDateFormat1() {
  	SimpleDateFormat result = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", HTTP_LOCALE);
  	result.setLenient(true);
  	result.setTimeZone(TimeZone.getTimeZone("GMT+0"));
  	return result;
  }

  // Sunday, 06-Nov-94 08:49:37 GMT
  private static DateFormat getDateFormat2() {
  	SimpleDateFormat result = new SimpleDateFormat("EEEE, dd-MMM-yy HH:mm:ss z", HTTP_LOCALE);
  	result.setLenient(true);
  	result.setTimeZone(TimeZone.getTimeZone("GMT+0"));
  	return result;
  }

  // Sun Nov  6 08:49:37 1994
  private static DateFormat getDateFormat3() {
  	SimpleDateFormat result = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", HTTP_LOCALE);
  	result.setLenient(true);
  	result.setTimeZone(TimeZone.getTimeZone("GMT+0"));
  	return result;
  }

  private static final DateFormat dateFormat1 = getDateFormat1();
  private static final DateFormat dateFormat2 = getDateFormat2();
  private static final DateFormat dateFormat3 = getDateFormat3();

  private static final ThreadLocal<DateFormat> DATE_FORMATTER = new ThreadLocal<>();

  public static final String formatDate(long date) {
    DateFormat formatter = DATE_FORMATTER.get();
    if (formatter == null) {
      formatter = getDateFormat1();
      DATE_FORMATTER.set(formatter);
    }
    return formatter.format(new Date(date));
  }

  public static final synchronized long unformatDate(String date) {
  	try {
  	  return dateFormat1.parse(date).getTime();
  	} catch (Exception ignored) {
  	  // Ignored
  	}
  	try {
  	  return dateFormat2.parse(date).getTime();
  	} catch (Exception ignored) {
  	  // Ignored
  	}
  	try {
  	  return dateFormat3.parse(date).getTime();
  	} catch (Exception e) {
  	  // Ignored
  	}
  	new Exception("could not parse: \""+date+"\"").printStackTrace();
  	return 0;
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

  /**
   * Return a string containing the code and the well-known status text, if
   * defined by RFC 2616. If the status text is unknown, returns informational
   * status text as defined by the ranges in RFC 2616. If the status code is
   * outside those ranges, returns "None".
   *
   * @throws IllegalArgumentException if the given code is not a three-digit
   *         number
   */
  public static String getStatusText(int code) {
  	return HttpResponseCode.getStatusText(code);
  }

  private CoreHelper() {
    // Disallow instantiation.
  }
}

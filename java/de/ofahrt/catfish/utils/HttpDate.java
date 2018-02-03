package de.ofahrt.catfish.utils;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class HttpDate {

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

  public static final synchronized long parseDate(String date) {
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

  private HttpDate() {
    // Not instantiable.
  }
}

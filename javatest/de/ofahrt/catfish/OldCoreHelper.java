package de.ofahrt.catfish;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class OldCoreHelper {

  static Locale loc = new Locale("en", "us");

  // Sun, 06 Nov 1994 08:49:37 GMT
  static DateFormat dateFormater1 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", loc);

  // Sunday, 06-Nov-94 08:49:37 GMT
  static DateFormat dateFormater2 = new SimpleDateFormat("EEEE, dd-MMM-yy HH:mm:ss z", loc);

  // Sun Nov  6 08:49:37 1994
  static DateFormat dateFormater3 = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", loc);

  static {
  	dateFormater1.setLenient(true);
  	dateFormater2.setLenient(true);
  	dateFormater3.setLenient(true);
  	dateFormater1.setTimeZone(TimeZone.getTimeZone("GMT+0"));
  	dateFormater2.setTimeZone(TimeZone.getTimeZone("GMT+0"));
  	dateFormater3.setTimeZone(TimeZone.getTimeZone("GMT+0"));
  }

  public static synchronized final String formatDate(long date) {
    return dateFormater1.format(new Date(date));
  }

  public static synchronized final long unformatDate(String date) {
  	try {
  	  return dateFormater1.parse(date).getTime();
  	} catch (Exception ignored) {
  	  // Ignored
  	}
  	try {
  	  return dateFormater2.parse(date).getTime();
  	} catch (Exception e) {
      // Ignored
  	}
  	try {
  	  return dateFormater3.parse(date).getTime();
  	} catch (Exception e) {
      // Ignored
  	}
  	throw new RuntimeException("could not parse: \""+date+"\"");
  }
}

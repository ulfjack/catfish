package de.ofahrt.catfish.webalizer;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.servlet.http.HttpServletRequest;

import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.bridge.ServletHelper;
import de.ofahrt.catfish.utils.HttpFieldName;

public final class WebalizerFormatter {

  private static final DateFormat DATE_FORMAT = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US);

  // Access to DateFormat must be synchronized.
  private static synchronized String formatDate(Date date) {
    return DATE_FORMAT.format(date);
  }

  public String format(HttpServletRequest request, HttpResponse response, int amount) {
  	// LogFormat "%h %l %u %t \"%r\" %>s %b \"%{Referer}i\" \"%{User-agent}i\""
  	// client unknown unknown time request-line(method,url,protocol) status-code bytes-sent referer user-agent
    String ua = request.getHeader(HttpFieldName.USER_AGENT);
    if (ua == null) {
      ua = "-";
    }
    String ref = request.getHeader(HttpFieldName.REFERER);
    if (ref == null) {
      ref = "-";
    }

    String logentry = request.getRemoteHost() + " - - ["+
        formatDate(new Date())+"] " +
        "\"" + request.getMethod() + " " +
        ServletHelper.getCompleteUrl(request) + " " +
        request.getProtocol() + "\" " + 
        response.getStatusCode() + " " + amount + " \"" + ref + "\" \"" + ua + "\" ";
    return logentry;
  }
}
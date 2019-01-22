package de.ofahrt.catfish.webalizer;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.Connection;

public final class WebalizerFormatter {

  private static final DateFormat DATE_FORMAT = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US);

  // Access to DateFormat must be synchronized.
  private static synchronized String formatDate(Date date) {
    return DATE_FORMAT.format(date);
  }

  public String format(Connection connection, HttpRequest request, HttpResponse response, int amount) {
  	// LogFormat "%h %l %u %t \"%r\" %>s %b \"%{Referer}i\" \"%{User-agent}i\""
  	// client unknown unknown time request-line(method,url,protocol) status-code bytes-sent referer user-agent
    String ua = request.getHeaders().get(HttpHeaderName.USER_AGENT);
    if (ua == null) {
      ua = "-";
    }
    String ref = request.getHeaders().get(HttpHeaderName.REFERER);
    if (ref == null) {
      ref = "-";
    }

    String logentry = connection.getRemoteAddress() + " - - ["+
        formatDate(new Date())+"] " +
        "\"" + request.getMethod() + " " +
        request.getUri() + " " +
        request.getVersion() + "\" " + 
        response.getStatusCode() + " " + amount + " \"" + ref + "\" \"" + ua + "\" ";
    return logentry;
  }
}
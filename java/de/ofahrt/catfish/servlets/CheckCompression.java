package de.ofahrt.catfish.servlets;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import de.ofahrt.catfish.bridge.ServletHelper;
import de.ofahrt.catfish.utils.MimeType;

public final class CheckCompression extends HttpServlet {

  private static final long serialVersionUID = 1L;

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MimeType.TEXT_HTML.toString());
    StringBuilder buffer = new StringBuilder();
    buffer.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\"\n"
        +"\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n");
    buffer.append("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n");
    buffer.append("<head><title>Check Compression</title></head>\n");
    buffer.append("<body>\n");

    if (ServletHelper.supportCompression(request)) {
      buffer.append("Your browser supports compression!");
    } else {
      buffer.append("Your browser does not support compression!");
    }
    buffer.append("<br/>\n<br/>\n<br/>\n");

    buffer.append(ServletHelper.getRequestText(request));
    buffer.append("</body>\n</html>\n");
    Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
    out.write(buffer.toString());
    out.close();
  }
}

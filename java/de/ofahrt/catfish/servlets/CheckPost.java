package de.ofahrt.catfish.servlets;

import de.ofahrt.catfish.model.HtmlEscape;
import de.ofahrt.catfish.utils.MimeType;
import java.io.IOException;
import java.io.Writer;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class CheckPost extends HttpServlet {

  private static final long serialVersionUID = 1L;

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MimeType.TEXT_HTML.toString());
    Writer out = response.getWriter();
    out.write("<html><title>Check Post</title><body>");

    //  	out.write("<form method=\"post\" enctype=\"multipart/form-data\">");
    out.write("<form method=\"post\" enctype=\"application/x-www-form-urlencoded\">");
    out.write("<input type=\"hidden\" name=\"a\" value=\"b\" />");
    out.write("<button type=\"submit\">Submit</button>");
    out.write("</form>");
    out.write("</body></html>");
    out.close();
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MimeType.TEXT_HTML.toString());
    Writer out = response.getWriter();
    out.write("<html><title>Check Post</title><body>");
    String a = request.getParameter("a");
    if (a != null) {
      out.write("<p>" + HtmlEscape.text(a) + "</p>");
    }

    //    out.write("<form method=\"post\" enctype=\"multipart/form-data\">");
    out.write("<form method=\"post\" enctype=\"application/x-www-form-urlencoded\">");
    out.write("<input type=\"hidden\" name=\"a\" value=\"b\" />");
    out.write("<button type=\"submit\">Submit</button>");
    out.write("</form>");
    out.write("</body></html>");
    out.close();
  }
}

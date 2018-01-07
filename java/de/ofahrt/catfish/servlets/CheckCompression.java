package de.ofahrt.catfish.servlets;

import java.io.IOException;
import java.io.Writer;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import de.ofahrt.catfish.utils.MimeType;
import de.ofahrt.catfish.utils.ServletHelper;

public final class CheckCompression extends HttpServlet {

  private static final long serialVersionUID = 1L;

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
  	response.setStatus(HttpServletResponse.SC_OK);
  	response.setContentType(MimeType.TEXT_HTML.toString());
  	Writer out = response.getWriter();
  	out.write("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\"\n"
  			+"\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n");
  	out.write("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n");
  	out.write("<head><title>Check Compression</title></head>\n");
  	out.write("<body>\n");

  	if (ServletHelper.supportCompression(request)) {
  		out.write("Your browser supports compression!");
  	} else {
  		out.write("Your browser does not support compression!");
  	}
  	out.write("<br/>\n<br/>\n<br/>\n");

  	out.write(ServletHelper.getRequestText(request));
  	out.write("</body>\n</html>\n");
  	out.close();
  }
}

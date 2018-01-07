package de.ofahrt.catfish.integration;

import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import de.ofahrt.catfish.utils.MimeType;

public class HttpRequestTestServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MimeType.APPLICATION_OCTET_STREAM.toString());
    OutputStream out = response.getOutputStream();
    out.write(new SerializableHttpServletRequest(request).serialize());
    out.close();
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    doGet(request, response);
  }
}

package de.ofahrt.catfish.servlets;

import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class RedirectServlet extends HttpServlet {

  private static final long serialVersionUID = 1L;

  private final String target;

  public RedirectServlet(String target) {
  	this.target = target;
  }

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    res.sendRedirect(target);
  }
}

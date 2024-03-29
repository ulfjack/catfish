package de.ofahrt.catfish;

import java.io.IOException;
import java.io.Writer;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import de.ofahrt.catfish.bridge.FileData;
import de.ofahrt.catfish.bridge.ServletHelper;
import de.ofahrt.catfish.utils.MimeType;

public final class TestServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final String X_REPLY_WITH = "X-Reply-With";

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String replyWith = request.getHeader(X_REPLY_WITH);
    if (replyWith != null) {
      response.setStatus(Integer.parseInt(replyWith));
      try (Writer out = response.getWriter()) {
        out.write("xyz");
      }
      return;
    }
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MimeType.TEXT_HTML.toString());
    response.setCharacterEncoding("UTF-8");
    try (Writer out = response.getWriter()) {
      out.write("");
    }
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    FileData[] fda = ServletHelper.parseFormData(request).files.values().toArray(new FileData[0]);
    for (FileData f : fda) {
      System.out.println(f.getName());
    }
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MimeType.TEXT_HTML.toString());
    response.setCharacterEncoding("UTF-8");
    @SuppressWarnings("resource") Writer out = response.getWriter();
    out.write("");
  }
}

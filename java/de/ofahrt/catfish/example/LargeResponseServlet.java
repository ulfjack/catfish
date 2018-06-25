package de.ofahrt.catfish.example;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import de.ofahrt.catfish.utils.MimeType;

public final class LargeResponseServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final String DATA = "0123456789";

  private final int size;

  public LargeResponseServlet(int size) {
    this.size = size;
  }

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MimeType.TEXT_HTML.toString());
    StringBuilder buffer = new StringBuilder();
    buffer.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\"\n"
        +"\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n");
    buffer.append("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n");
    buffer.append("<head><title>Large Response</title></head>\n");
    buffer.append("<body>\n");
    int i;
    for (i = 0; i < size; i += DATA.length()) {
      buffer.append(DATA);
    }
    for (; i < size; i++) {
      buffer.append('a');
    }
    buffer.append("</body>\n</html>\n");
    Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
    out.write(buffer.toString());
    out.close();
  }
}

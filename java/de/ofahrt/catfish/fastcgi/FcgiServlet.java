package de.ofahrt.catfish.fastcgi;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import de.ofahrt.catfish.fastcgi.IncrementalFcgiResponseParser.Callback;
import de.ofahrt.catfish.fastcgi.IncrementalFcgiResponseParser.MalformedResponseException;
import de.ofahrt.catfish.model.HttpHeaderName;

public class FcgiServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  public FcgiServlet() {
  }

  @SuppressWarnings("resource")
  @Override
  protected synchronized void doGet(HttpServletRequest req, final HttpServletResponse res)
      throws ServletException, IOException {
    FastCgiConnection connection = FastCgiConnection.connect("localhost", 12345);
    Record record = new Record();
    record.setType(FastCgiConstants.FCGI_BEGIN_REQUEST);
    record.setRequestId(1);
    record.setContent(new byte[] {0, 1, 1, 0, 0, 0, 0, 0});
    connection.write(record);

    record.setType(FastCgiConstants.FCGI_PARAMS);
    record.setRequestId(1);
    Map<String, String> map = new LinkedHashMap<>();
    map.put("SCRIPT_NAME", "/hello.php");
    map.put("SCRIPT_FILENAME", "/home/ulfjack/Projects/catfish/hello.php");
    map.put("REQUEST_METHOD", "GET");
    map.put("CONTENT_LENGTH", "0");
    map.put("PATH_INFO", "/");
    map.put("SERVER_NAME", "localhost");
    map.put("SERVER_PORT", "8080");
    map.put("QUERY_STRING", req.getQueryString());
    record.setContentAsMap(map);
    connection.write(record);

    record.setType(FastCgiConstants.FCGI_STDIN);
    record.setRequestId(1);
    record.setContent(new byte[0]);
    connection.write(record);

    res.setStatus(200);
    final OutputStream out = res.getOutputStream();
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(new Callback() {

      @Override
      public void addHeader(String key, String value) {
        key = HttpHeaderName.canonicalize(key);
        if (HttpHeaderName.CONTENT_TYPE.equals(key)) {
          res.setContentType(value);
        } else {
          System.err.println(key + "=" + value);
        }
      }

      @Override
      public void addData(byte[] data, int offset, int length) {
        try {
          out.write(data, offset, length);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });
    while (true) {
      record = connection.read();
//      System.out.println(record);
      if (record.getType() == FastCgiConstants.FCGI_STDOUT) {
        try {
          parser.parse(record.getContent());
        } catch (MalformedResponseException e) {
          throw new IOException(e);
        }
//        out.write(record.getContent());
      } else if (record.getType() == FastCgiConstants.FCGI_END_REQUEST) {
        break;
      }
    }
    connection.close();
  }
}

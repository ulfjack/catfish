package de.ofahrt.catfish.fastcgi;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

final class FastCgiConnection {

  public static FastCgiConnection connect(String server, int port) throws IOException {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress(server, port));
    socket.setTcpNoDelay(true);
    return new FastCgiConnection(socket);
  }

  private final Socket socket;

  private FastCgiConnection(Socket socket) {
    this.socket = socket;
  }

  public void write(Record record) throws IOException {
    OutputStream out = socket.getOutputStream();
    record.writeTo(out);
    out.flush();
  }

  public Record read() throws IOException {
    Record record = new Record();
    record.readFrom(socket.getInputStream());
    return record;
  }

  public void close() throws IOException {
    socket.close();
  }

  public static void main(String[] args) throws Exception {
    FastCgiConnection connection = connect("localhost", 12345);
    Record record = new Record();
    record.setType(FastCgiConstants.FCGI_GET_VALUES);
    record.setRequestId(FastCgiConstants.FCGI_NULL_REQUEST_ID);
    record.setContentAsKeys("FCGI_MAX_CONNS", "FCGI_MAX_REQS", "FCGI_MPXS_CONNS");
//    connection.write(record);
//    System.out.println(connection.read());

    record.setType(FastCgiConstants.FCGI_BEGIN_REQUEST);
    record.setRequestId(1);
    record.setContent(new byte[] {0, 1, 1, 0, 0, 0, 0, 0});
    connection.write(record);

    record.setType(FastCgiConstants.FCGI_PARAMS);
    record.setRequestId(1);
    Map<String, String> map = new LinkedHashMap<String, String>();
    map.put("SCRIPT_NAME", "/hello.php");
    map.put("SCRIPT_FILENAME", "/home/ulfjack/Projects/catfish/hello.php");
    map.put("REQUEST_METHOD", "GET");
    map.put("CONTENT_LENGTH", "0");
    map.put("PATH_INFO", "/");
    map.put("SERVER_NAME", "localhost");
    map.put("SERVER_PORT", "8080");
    map.put("QUERY_STRING", "test.php");
    record.setContentAsMap(map);
    connection.write(record);

    record.setType(FastCgiConstants.FCGI_STDIN);
    record.setRequestId(1);
    record.setContent(new byte[0]);
    connection.write(record);

    while (true) {
      record = connection.read();
      System.out.println(record);
      if (record.getType() == FastCgiConstants.FCGI_END_REQUEST) {
        break;
      }
    }
    connection.close();
  }
}

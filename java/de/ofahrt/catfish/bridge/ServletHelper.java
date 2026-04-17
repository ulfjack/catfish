package de.ofahrt.catfish.bridge;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.upload.FormEntry;
import de.ofahrt.catfish.upload.UrlEncodedParser;
import de.ofahrt.catfish.utils.MimeType;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ServletHelper {
  private static final String DEFAULT_CHARSET = "UTF-8";

  private static final Pattern nameExtractorPattern = Pattern.compile(".* name=\"(.*)\".*");
  private static final Pattern filenameExtractorPattern =
      Pattern.compile(".* name=\"(.*)\".* filename=\"(.*)\".*");

  public static final String formatText(String what, boolean fixed) {
    StringBuffer result = new StringBuffer();
    if (fixed) {
      result.append("<pre>");
    }
    for (int i = 0; i < what.length(); i++) {
      char c = what.charAt(i);
      switch (c) {
        case 13 -> {} // skip CR
        case 10 -> result.append("<br/>");
        case '<' -> result.append("&lt;");
        case '>' -> result.append("&gt;");
        case '&' -> result.append("&amp;");
        case '"' -> result.append("&quot;");
        default -> result.append(c);
      }
    }
    if (fixed) {
      result.append("</pre>");
    }
    return result.toString();
  }

  // public static void generate(ResponseCode code, MimeType mimeType,
  // HttpServletResponse response, Generator generator) throws IOException
  // {
  // StringWriter temp = new StringWriter(5000);
  // generator.generate(temp);
  // response.setHeader(HeaderKey.CACHE_CONTROL.toString(), "no-cache");
  // response.setHeader(HeaderKey.PRAGMA.toString(), "no-cache");
  // response.setStatus(code.getCode());
  // response.setContentType(mimeType.toString());
  // response.setCharacterEncoding("UTF-8");
  // Writer sout = response.getWriter();
  // sout.append(temp.getBuffer());
  // }
  //
  // public static void generate(MimeType mimeType, HttpServletResponse
  // response, Generator generator) throws IOException
  // { generate(ResponseCode.OK, mimeType, response, generator); }

  public static void setBodyString(HttpServletResponse response, MimeType mimeType, String s)
      throws IOException {
    response.setContentType(mimeType.toString());
    Writer out = response.getWriter();
    out.write(s);
    out.close();
  }

  public static void setBodyInputStream(HttpServletResponse response, MimeType type, InputStream in)
      throws IOException {
    response.setContentType(type.toString());
    OutputStream out = response.getOutputStream();
    int i = 0;
    byte[] buffer = new byte[1024];
    while ((i = in.read(buffer)) != -1) {
      out.write(buffer, 0, i);
    }
    out.close();
  }

  public static void setBodyReader(HttpServletResponse response, MimeType type, Reader in)
      throws IOException {
    response.setContentType(type.toString());
    Writer out = response.getWriter();
    int i = 0;
    char[] buffer = new char[1024];
    while ((i = in.read(buffer)) != -1) out.write(buffer, 0, i);
    out.close();
  }

  public static void setBodyFile(HttpServletResponse response, MimeType type, String name)
      throws IOException {
    File f = new File(name);
    if (!f.canRead()) throw new IOException("Cannot read file \"" + name + "\"!");

    FileInputStream fis = null;
    try {
      fis = new FileInputStream(name);
      setBodyInputStream(response, type, fis);
    } finally {
      if (fis != null) fis.close();
    }
  }

  public static String getFilename(HttpServletRequest request) {
    String filename = URI.create(request.getRequestURI()).getPath();
    int j = filename.lastIndexOf('/');
    if (j != -1) {
      filename = filename.substring(j + 1);
    }
    if ("".equals(filename)) {
      filename = "index";
    }
    return filename;
  }

  public static boolean supportCompression(HttpServletRequest request) {
    String temp = request.getHeader(HttpHeaderName.ACCEPT_ENCODING);
    return temp != null && temp.toLowerCase(Locale.ENGLISH).indexOf("gzip") >= 0;
  }

  public static Map<String, String> parseQuery(HttpServletRequest request) {
    Map<String, String> result = new TreeMap<>();
    String queryData = request.getQueryString();
    if (queryData != null) {
      for (FormEntry entry :
          new UrlEncodedParser().parse(queryData.getBytes(StandardCharsets.UTF_8))) {
        result.put(entry.getName(), entry.getValue());
      }
    }
    return result;
  }

  private static byte[] readByteArray(InputStream in, int length) throws IOException {
    int bodyReceived = 0;
    byte[] ba = new byte[length];
    while (bodyReceived < ba.length) {
      int i = in.read(ba, bodyReceived, ba.length - bodyReceived);
      if (i == -1) {
        throw new IOException("Unexpected end of stream!");
      }
      bodyReceived += i;
    }
    return ba;
  }

  private static void parseFormData(FormData result, InputStream in, int expected)
      throws IOException {
    boolean requestFinished = false;
    int b = 0;
    String line = "";
    Map<String, String> tempInfo = new TreeMap<>();
    while (!requestFinished) {
      b = in.read();
      if (b < 0) {
        b = -1;
      } else {
        expected--;
        if ((b < 9) || (b > 255)) {
          b = 0;
        }
        if ((b > 10) && (b < 32)) {
          b = 0;
        }
      }

      switch (b) {
        case -1 -> requestFinished = true;
        case 0 -> {}
        case 10 -> {
          if ("".equals(line)) {
            requestFinished = true;
          } else {
            int i = line.indexOf(':');
            // FIXME: Use canonicalizer!
            String s1 = line.substring(0, i).trim().toLowerCase(Locale.ENGLISH);
            String s2 = line.substring(i + 1).trim();
            tempInfo.put(s1, s2);
          }
          line = "";
        }
        default -> line += (char) b;
      }
    }

    byte[] ba = readByteArray(in, expected - 2);
    String contentType = tempInfo.get("content-type");
    String contentDisposition = tempInfo.get("content-disposition");
    if (contentType == null) {
      Matcher m = nameExtractorPattern.matcher(contentDisposition);
      if (m.matches()) {
        String name = m.group(1);
        result.data.put(name, new String(ba));
      } else {
        throw new IllegalArgumentException("Unrecognized form data!");
      }
    } else {
      Matcher m = filenameExtractorPattern.matcher(contentDisposition);
      if (m.matches()) {
        String name = m.group(1);
        String tempname = m.group(2);
        result.files.put(name, new FileData(name, tempname, ba));
      } else {
        throw new IllegalArgumentException("Unrecognized form data!");
      }
    }
  }

  static FormData parseFormData(int clen, InputStream in, String ctHeader) throws IOException {
    FormData result = new FormData();
    byte[] ba = readByteArray(in, clen);
    String contentType = ctHeader;
    {
      int i = ctHeader.indexOf(';');
      if (i >= 0) {
        contentType = ctHeader.substring(0, i).trim();
        ctHeader = ctHeader.substring(i + 1).trim();
      }
    }

    if ("multipart/form-data".equalsIgnoreCase(contentType)) {
      int k = ctHeader.indexOf("=");
      String boundary = "--" + ctHeader.substring(k + 1).trim();
      byte[] bounds = boundary.getBytes();
      int numIndex = 0;
      int[] indexes = new int[30];

      for (int i = 0; i < ba.length - bounds.length; i++) {
        boolean found = true;
        for (int j = 0; j < bounds.length; j++) {
          if (ba[i + j] != bounds[j]) {
            found = false;
            break;
          }
        }

        if (found) {
          indexes[numIndex++] = i;
        }
      }

      for (int i = 0; i < numIndex - 1; i++) {
        int start = indexes[i] + bounds.length + 2;
        int length = indexes[i + 1] - indexes[i] - bounds.length - 2;
        parseFormData(result, new ByteArrayInputStream(ba, start, length), length);
      }
    }

    if ("application/x-www-form-urlencoded".equalsIgnoreCase(contentType)) {
      for (FormEntry entry : new UrlEncodedParser().parse(ba)) {
        result.data.put(entry.getName(), entry.getValue());
      }
    }
    return result;
  }

  public static FormData parseFormData(HttpServletRequest request) throws IOException {
    String clen = request.getHeader(HttpHeaderName.CONTENT_LENGTH);
    String ctHeader = request.getHeader(HttpHeaderName.CONTENT_TYPE);
    if ((clen != null) && (ctHeader != null)) {
      return parseFormData(Integer.parseInt(clen), request.getInputStream(), ctHeader);
    }
    return new FormData();
  }

  public static String getCompleteUrl(HttpServletRequest request) {
    StringBuffer result = request.getRequestURL();
    String query = request.getQueryString();
    if (query != null) {
      result.append("?").append(query);
    }
    return result.toString();
  }

  public static final String requestToString(HttpServletRequest request) {
    StringBuffer out = new StringBuffer();
    out.append(request.getMethod())
        .append(" ")
        .append(getCompleteUrl(request))
        .append(" ")
        .append(request.getProtocol())
        .append("\n");
    Enumeration<?> it = request.getHeaderNames();
    while (it.hasMoreElements()) {
      String key = (String) it.nextElement();
      out.append(key).append(": ").append(request.getHeader(key)).append("\n");
    }
    out.append("Query Parameters:\n");
    Map<String, String> queries = parseQuery(request);
    for (Map.Entry<String, String> e : queries.entrySet()) {
      out.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
    }
    try {
      FormData formData = parseFormData(request);
      out.append("Post Parameters:\n");
      for (Map.Entry<String, String> e : formData.data.entrySet()) {
        out.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
      }
    } catch (IOException e) {
      out.append("Exception trying to parse post parameters:\n");
      out.append(throwableToString(e));
    }
    return out.toString();
  }

  public static final String throwableToString(Throwable e) {
    StringWriter buffer = new StringWriter();
    e.printStackTrace(new PrintWriter(buffer));
    return buffer.toString();
  }

  public static final String getRequestText(HttpServletRequest req) {
    return formatText(requestToString(req), true);
  }
}

package de.ofahrt.catfish.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import de.ofahrt.catfish.model.Connection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.utils.HttpDate;
import de.ofahrt.catfish.utils.MimeType;
import de.ofahrt.catfish.utils.MimeTypeRegistry;

public final class DirectoryHandler implements HttpHandler {
  private final String internalPath;

  public DirectoryHandler(String internalPath) {
    if (!internalPath.endsWith("/")) {
      throw new IllegalArgumentException("Path must end with a '/'");
    }
    this.internalPath = internalPath;
  }

  @Override
  public void handle(Connection connection, HttpRequest request, HttpResponseWriter responseWriter) throws IOException {
    if (!HttpMethodName.GET.equals(request.getMethod())) {
      responseWriter.commitBuffered(StandardResponses.METHOD_NOT_ALLOWED);
      return;
    }

    File f;
    try {
      f = getFile(request);
    } catch (URISyntaxException e) {
      responseWriter.commitBuffered(StandardResponses.BAD_REQUEST);
      return;
    }
    if (!f.exists() || !f.isFile() || f.isHidden() || !f.canRead()) {
      // Silently return 404.
      responseWriter.commitBuffered(StandardResponses.NOT_FOUND);
      return;
    }

    long lastModified = f.lastModified();
    HttpHeaders headers = HttpHeaders.of(
        HttpHeaderName.LAST_MODIFIED, HttpDate.formatDate(lastModified),
        HttpHeaderName.CONTENT_TYPE, guessContentType(f));

    String ifModifiedSinceText = request.getHeaders().get(HttpHeaderName.IF_MODIFIED_SINCE);
    if (ifModifiedSinceText != null) {
      long ifModifiedSince = HttpDate.parseDate(ifModifiedSinceText);
      if (ifModifiedSince >= lastModified) {
        HttpResponse response = StandardResponses.NOT_MODIFIED.withHeaderOverrides(headers);
        responseWriter.commitBuffered(response);
        return;
      }
    }

    HttpResponse response = StandardResponses.OK.withHeaderOverrides(headers);
    try (OutputStream out = responseWriter.commitStreamed(response)) {
      try (FileInputStream in = new FileInputStream(f)) {
        byte[] buffer = new byte[1024];
        int len;
        while ((len = in.read(buffer)) > 0) {
          out.write(buffer, 0, len);
        }
      }
    }
  }

  private File getFile(HttpRequest request) throws URISyntaxException {
    String filename = getFilename(request);
    return new File(new File(internalPath), filename);
  }

  private static String getFilename(HttpRequest request) throws URISyntaxException {
    String filename = new URI(request.getUri()).getPath();
    int j = filename.lastIndexOf('/');
    if (j != -1) {
      filename = filename.substring(j + 1);
    }
    if ("".equals(filename)) {
      filename = "index";
    }
    return filename;
  }

  private static final String guessContentType(File f) {
    MimeType mimeType = MimeTypeRegistry.guessFromFilename(f.getName());
    if (mimeType.isText()) {
      return mimeType.toString() + "; charset=UTF-8";
    } else {
      return mimeType.toString();
    }
  }
}

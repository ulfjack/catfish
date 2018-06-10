package de.ofahrt.catfish.api;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import de.ofahrt.catfish.utils.HttpDate;
import de.ofahrt.catfish.utils.HttpFieldName;
import de.ofahrt.catfish.utils.HttpResponseCode;
import de.ofahrt.catfish.utils.MimeType;
import de.ofahrt.catfish.utils.MimeTypeRegistry;

final class FileResponse implements HttpResponse {
  private final File f;

  public FileResponse(File f) {
    this.f = f;
  }

  private static final String guessContentType(File f) {
    MimeType mimeType = MimeTypeRegistry.guessFromFilename(f.getName());
    if (mimeType.isText()) {
      return mimeType.toString() + "; charset=UTF-8";
    } else {
      return mimeType.toString();
    }
  }

  @Override
  public int getStatusCode() {
    return HttpResponseCode.OK.getCode();
  }

  @Override
  public HttpHeaders getHeaders() {
    return HttpHeaders.of(
        HttpFieldName.LAST_MODIFIED, HttpDate.formatDate(f.lastModified()),
        HttpFieldName.CONTENT_TYPE, guessContentType(f));
  }

  @Override
  public void writeBodyTo(OutputStream out) throws IOException {
    try (InputStream in = new FileInputStream(f)) {
      int i = 0;
      byte[] buffer = new byte[1024];
      while ((i = in.read(buffer)) != -1) {
        out.write(buffer, 0, i);
      }
    }
  }
}

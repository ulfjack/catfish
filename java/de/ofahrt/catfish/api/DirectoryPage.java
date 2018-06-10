package de.ofahrt.catfish.api;

import java.io.File;
import java.io.IOException;

import de.ofahrt.catfish.utils.HttpDate;
import de.ofahrt.catfish.utils.HttpFieldName;

public final class DirectoryPage implements HttpPage {
  private final String internalPath;

  public DirectoryPage(String internalPath) {
    if (!internalPath.endsWith("/")) {
      throw new IllegalArgumentException("Path must end with a '/'");
    }
    this.internalPath = internalPath;
  }

  private File getFile(HttpGetRequest request) {
    String filename = request.getFilename();
    return new File(new File(internalPath), filename);
  }

  @Override
  public HttpResponse handleGet(HttpGetRequest request) throws IOException {
    File f = getFile(request);
    if (!f.exists() || !f.isFile() || f.isHidden() || !f.canRead()) {
      // Silently return 404.
      return HttpResponse.NOT_FOUND;
    }

    long lastModified = f.lastModified();
    String ifModifiedSinceText = request.getHeader(HttpFieldName.IF_MODIFIED_SINCE);
    if (ifModifiedSinceText != null) {
      long ifModifiedSince = HttpDate.parseDate(ifModifiedSinceText);
      if (ifModifiedSince >= lastModified) {
        return HttpResponse.NOT_MODIFIED;
      }
    }
    return new FileResponse(f);
  }

  @Override
  public HttpResponse handlePost(HttpPostRequest request) throws IOException {
    return HttpResponse.METHOD_NOT_ALLOWED;
  }
}

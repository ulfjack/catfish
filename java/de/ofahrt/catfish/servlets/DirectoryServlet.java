package de.ofahrt.catfish.servlets;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import de.ofahrt.catfish.bridge.ServletHelper;
import de.ofahrt.catfish.utils.MimeType;
import de.ofahrt.catfish.utils.MimeTypeRegistry;

public final class DirectoryServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private final String internalPath;

  public DirectoryServlet(String internalPath) {
    if (!internalPath.endsWith("/")) {
      throw new IllegalArgumentException("Path must end with a '/'");
    }
    this.internalPath = internalPath;
  }

  private File getFile(HttpServletRequest req) {
    String filename = ServletHelper.getFilename(req);
    return new File(new File(internalPath), filename);
  }

  @Override
  protected long getLastModified(HttpServletRequest req) {
    File f = getFile(req);
    if (!f.exists()) {
      return -1;
    }
    if (f.isHidden()) {
      return -1;
    }
    if (!f.canRead()) {
      return -1;
    }
    return f.lastModified();
  }

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    File f = getFile(req);
    if (!f.exists()) {
      throw new FileNotFoundException("File \"" + f.getPath() + "\" not found!");
    }
    if (f.isHidden()) {
      throw new IOException("File \"" + f.getPath() + "\" is hidden!");
    }
    if (!f.canRead()) {
      throw new IOException("File \"" + f.getPath() + "\" not readable!");
    }
    res.setStatus(HttpServletResponse.SC_OK);
    MimeType mimeType = MimeTypeRegistry.guessFromFilename(ServletHelper.getFilename(req));
    if (mimeType.isText()) {
      res.setContentType(mimeType.toString() + "; charset=UTF-8");
    } else {
      res.setContentType(mimeType.toString());
    }
    ServletHelper.setBodyFile(res, MimeTypeRegistry.guessFromFilename(f.getPath()), f.getPath());
  }
}

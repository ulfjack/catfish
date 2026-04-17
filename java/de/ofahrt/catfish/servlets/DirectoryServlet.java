package de.ofahrt.catfish.servlets;

import de.ofahrt.catfish.bridge.ServletHelper;
import de.ofahrt.catfish.utils.MimeType;
import de.ofahrt.catfish.utils.MimeTypeRegistry;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class DirectoryServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private final Path root;

  public DirectoryServlet(String internalPath) {
    Objects.requireNonNull(internalPath, "internalPath");
    if (!internalPath.endsWith("/")) {
      throw new IllegalArgumentException("Path must end with a '/'");
    }
    this.root = Path.of(internalPath).normalize();
  }

  private File getFile(HttpServletRequest req) throws FileNotFoundException {
    String filename = ServletHelper.getFilename(req);
    Path resolved = root.resolve(filename).normalize();
    if (!resolved.startsWith(root)) {
      throw new FileNotFoundException();
    }
    return resolved.toFile();
  }

  @Override
  protected long getLastModified(HttpServletRequest req) {
    File f;
    try {
      f = getFile(req);
    } catch (FileNotFoundException e) {
      return -1;
    }
    if (!f.exists() || f.isHidden() || !f.canRead()) {
      return -1;
    }
    return f.lastModified();
  }

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    File f = getFile(req);
    if (!f.exists() || f.isHidden() || !f.canRead()) {
      throw new FileNotFoundException();
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

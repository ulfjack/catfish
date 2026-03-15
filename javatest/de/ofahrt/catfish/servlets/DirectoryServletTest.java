package de.ofahrt.catfish.servlets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.client.TestingCatfishHttpClient;
import de.ofahrt.catfish.model.HttpResponse;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DirectoryServletTest {

  private Path tempDir;

  @Before
  public void setUp() throws IOException {
    tempDir = Files.createTempDirectory("DirectoryServletTest");
  }

  @After
  public void tearDown() throws IOException {
    File[] files = tempDir.toFile().listFiles();
    if (files != null) {
      for (File f : files) {
        f.delete();
      }
    }
    Files.deleteIfExists(tempDir);
  }

  @Test
  public void constructorRejectsPathWithoutTrailingSlash() {
    try {
      new DirectoryServlet("/no-trailing-slash");
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void existingTextFileReturns200() throws Exception {
    Path file = tempDir.resolve("hello.txt");
    Files.write(file, "Hello, World!".getBytes(StandardCharsets.UTF_8));
    TestingCatfishHttpClient client =
        TestingCatfishHttpClient.createClientForServlet(
            new DirectoryServlet(tempDir.toString() + "/"));
    HttpResponse response = client.get("http://localhost/hello.txt");
    assertEquals(200, response.getStatusCode());
  }

  @Test
  public void missingFileThrowsFileNotFoundException() throws Exception {
    TestingCatfishHttpClient client =
        TestingCatfishHttpClient.createClientForServlet(
            new DirectoryServlet(tempDir.toString() + "/"));
    try {
      client.get("http://localhost/nonexistent.txt");
      fail("Expected FileNotFoundException");
    } catch (FileNotFoundException expected) {
      // expected
    }
  }

  @Test
  public void hiddenFileThrowsIOException() throws Exception {
    Path file = tempDir.resolve(".hidden");
    Files.write(file, "hidden content".getBytes(StandardCharsets.UTF_8));
    TestingCatfishHttpClient client =
        TestingCatfishHttpClient.createClientForServlet(
            new DirectoryServlet(tempDir.toString() + "/"));
    try {
      client.get("http://localhost/.hidden");
      fail("Expected IOException");
    } catch (IOException expected) {
      // expected
    }
  }
}

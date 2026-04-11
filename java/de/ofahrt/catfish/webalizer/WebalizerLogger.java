package de.ofahrt.catfish.webalizer;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.RequestOutcome;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class WebalizerLogger implements HttpServerListener {

  private class LogFileWriter implements Runnable {
    private PrintStream out = null;

    private void checkFile() {
      if ((out == null) || out.checkError()) {
        if (out != null) {
          out.close();
          out = null;
        }
        File f = new File(fileName);
        try {
          if (!f.createNewFile() && !f.exists()) throw new IOException("Could not create file!");
          out = new PrintStream(new FileOutputStream(f, true));
        } catch (IOException e) {
          // We can't really do anything here.
          // We could either not create the output file, or not open it for writing.
          // We have no way of logging this error.
        }
      }
    }

    @Override
    public void run() {
      String logentry = null;
      try {
        while (true) {
          logentry = jobs.take();
          try {
            checkFile();
            if (out != null) {
              out.println(logentry);
              out.flush();
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private final String fileName;
  private final BlockingQueue<String> jobs = new LinkedBlockingQueue<>();
  private final WebalizerFormatter formatter = new WebalizerFormatter();

  public WebalizerLogger(String filename) {
    this.fileName = filename;
    Thread t = new Thread(new LogFileWriter());
    t.setDaemon(true);
    t.start();
  }

  @Override
  public void onRequestComplete(
      UUID requestId,
      String originHost,
      int originPort,
      HttpRequest request,
      RequestOutcome outcome) {
    if (outcome.response() == null) {
      return;
    }
    String logentry =
        formatter.format(null, request, outcome.response(), (int) outcome.bytesSent());
    try {
      jobs.put(logentry);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}

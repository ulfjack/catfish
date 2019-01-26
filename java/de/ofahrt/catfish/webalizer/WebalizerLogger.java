package de.ofahrt.catfish.webalizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import de.ofahrt.catfish.HttpServerListener;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.Connection;

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
				  if (!f.createNewFile() && !f.exists())
			  		throw new IOException("Could not create file!");
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
  public void notifySent(Connection connection, HttpRequest request, HttpResponse response, int amount) {
  	String logentry = formatter.format(connection, request, response, amount);
    try {
      jobs.put(logentry);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
package de.ofahrt.catfish.spider;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringReader;

import org.w3c.dom.Document;
import org.w3c.tidy.Tidy;
import org.w3c.tidy.TidyMessage;
import org.w3c.tidy.TidyMessage.Level;
import org.w3c.tidy.TidyMessageListener;

public class HtmlParser {

  public Document parse(String data) {
    Tidy tidy = new Tidy();
    tidy.setQuiet(true);
    final StringBuffer messages = new StringBuffer();
    tidy.setMessageListener(new TidyMessageListener() {
      private String format(TidyMessage message) {
        StringBuffer result = new StringBuffer();
        result.append(message.getLevel());
        result.append(" line ").append(message.getLine());
        result.append(" column ").append(message.getColumn());
        result.append(" - ").append(message.getMessage());
        result.append("\n");
        return result.toString();
      }

      @Override
      public void messageReceived(TidyMessage message) {
        if (message.getLevel() == Level.ERROR) {
          throw new RuntimeException(format(message));
        }
        messages.append(format(message));
      }
    });
    tidy.setErrout(new PrintWriter(new ByteArrayOutputStream()));
    return tidy.parseDOM(new StringReader(data), null);
  }
}

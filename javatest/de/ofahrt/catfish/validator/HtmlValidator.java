package de.ofahrt.catfish.validator;

import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;

import org.w3c.tidy.Tidy;
import org.w3c.tidy.TidyMessage;
import org.w3c.tidy.TidyMessageListener;

public class HtmlValidator {

  public void validate(InputStream in) {
    Tidy tidy = new Tidy();
    tidy.setXHTML(true);
    tidy.setQuiet(true);
    final StringBuffer errors = new StringBuffer();
    tidy.setMessageListener(new TidyMessageListener() {
      private String format(TidyMessage message) {
        StringBuffer result = new StringBuffer();
        result.append("line ").append(message.getLine());
        result.append(" column ").append(message.getColumn());
        result.append(" - ").append(message.getMessage());
        result.append("\n");
        return result.toString();
      }

      @Override
      public void messageReceived(TidyMessage message) {
        errors.append(format(message));
      }
    });
    tidy.setErrout(new PrintWriter(new ByteArrayOutputStream()));
    tidy.parse(in, new ByteArrayOutputStream());
    if (errors.length() != 0) {
      fail(errors.toString());
    }
  }
}

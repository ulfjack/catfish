package de.ofahrt.catfish.validator;

import org.junit.Test;
import org.xml.sax.SAXParseException;

public class HtmlValidatorTest {

  @Test
  public void simpleValid() throws Exception {
    new HtmlValidator2().validate("<!doctype html><html><head><body>xxx <br> VALID HTML!");
  }

  @Test
  public void simple() throws Exception {
    new HtmlValidator2().validate("<!doctype html><body>xxx <br> NOT VALID HTML! </other>");
  }

  @Test
  public void simple2() throws Exception {
    new HtmlValidator2().validate("<!doctype html><!-- comment -- or not? -->");
  }

  @Test
  public void badEntity() throws Exception {
    new HtmlValidator2().validate("<!doctype html>&copy");
  }

  @Test
  public void badTag() throws Exception {
    new HtmlValidator2().validate("<!doctype html><capton>");
  }

  @Test
  public void badNesting() throws Exception {
    new HtmlValidator2().validate("<!doctype html><table><hr>");
  }

  @Test(expected = SAXParseException.class)
  public void simpleFailure() throws Exception {
    new HtmlValidator2().validate("xxx <br> NOT VALID HTML! </crslprnft>");
  }
}

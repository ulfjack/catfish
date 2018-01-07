package de.ofahrt.catfish.utils;

import java.io.IOException;
import java.io.StringReader;

import nu.validator.htmlparser.common.DoctypeExpectation;
import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.dom.HtmlDocumentBuilder;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

public class HtmlValidator2 {

  public void validate(String data) throws SAXException, IOException {
    HtmlDocumentBuilder builder = new HtmlDocumentBuilder();
    builder.setReportingDoctype(true);
    builder.setDoctypeExpectation(DoctypeExpectation.HTML);

    builder.setXmlPolicy(XmlViolationPolicy.FATAL);

    builder.setErrorHandler(new ErrorHandler() {

      @Override
      public void warning(SAXParseException exception) throws SAXException {
        System.err.println(exception.getLineNumber() + ":" + exception.getColumnNumber() + " " + exception.getMessage());
      }
      
      @Override
      public void fatalError(SAXParseException exception) throws SAXException {
        System.err.println(exception.getLineNumber() + ":" + exception.getColumnNumber() + " " + exception.getMessage());
        throw exception;
      }
      
      @Override
      public void error(SAXParseException exception) throws SAXException {
        System.err.println(exception.getLineNumber() + ":" + exception.getColumnNumber() + " " + exception.getMessage());
        throw exception;
      }
    });
    Document document = builder.parse(new InputSource(new StringReader(data)));
    recursiveWalk(document, 0);
//    System.out.println(document.getChildNodes().item(0));
  }

  private void recursiveWalk(Node node, int indent) {
    if (indent > 0) {
      System.out.printf("%" + indent + "s", "");
    }
    System.out.println(node.getNodeName());
    NodeList nodes = node.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      recursiveWalk(nodes.item(i), indent + 2);
    }
  }
}

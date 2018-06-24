package de.ofahrt.catfish;

import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;

import org.junit.runner.RunWith;

import de.ofahrt.catfish.api.Connection;
import de.ofahrt.catfish.bridge.RequestImpl;
import de.ofahrt.catfish.bridge.ResponsePolicy;
import info.adams.junit.NamedParameterized;
import info.adams.junit.NamedParameterized.Parameters;

@RunWith(NamedParameterized.class)
public class IncrementalHttpParserIncrementalTest extends HttpParserTest {

  @Parameters
  public static Collection<Object[]> data() {
  	ArrayList<Object[]> result = new ArrayList<>();
  	result.add(new Object[] {"No split", new int[] { Integer.MAX_VALUE }});
  	result.add(new Object[] {"10-infty", new int[] { 10, Integer.MAX_VALUE }});
  	result.add(new Object[] {"4-4-4-4-4-4-infty", new int[] { 4, 4, 4, 4, 4, 4, Integer.MAX_VALUE }});
  	result.add(new Object[] {"256*1-infty", new int[] {
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  			Integer.MAX_VALUE}});
  	return result;
  }

  private final String name;
  private final int[] lengths;

  public IncrementalHttpParserIncrementalTest(String name, int[] lengths) {
  	this.name = name;
  	this.lengths = lengths.clone();
  }

  public String getName() {
    return name;
  }

  @Override
  public RequestImpl parse(byte[] data) throws Exception {
  	IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
  	int pos = 0;
  	for (int i = 0; i < lengths.length; i++) {
  		int len = Math.min(data.length-pos, lengths[i]);
  		int consumed = parser.parse(data, pos, len);
  		assertTrue(consumed != 0);
  		pos += len;
  		if (pos == data.length) {
  		  break;
  		}
  		if (parser.isDone()) {
  		  break;
  		}
  	}
  	assertTrue(parser.isDone());
  	return new RequestImpl(
  	    parser.getRequest(),
  	    new Connection(
  	        new InetSocketAddress("127.0.0.1", 8080),
  	        new InetSocketAddress("127.0.0.1", 1234),
  	        false),
  	    null,
  	    ResponsePolicy.EMPTY,
  	    null);
  }
}

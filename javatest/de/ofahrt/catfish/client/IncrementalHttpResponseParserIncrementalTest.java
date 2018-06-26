package de.ofahrt.catfish.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Collection;
import org.junit.runner.RunWith;
import de.ofahrt.catfish.model.HttpResponse;
import info.adams.junit.NamedParameterized;
import info.adams.junit.NamedParameterized.Parameters;

@RunWith(NamedParameterized.class)
public class IncrementalHttpResponseParserIncrementalTest extends HttpResponseParserTest {

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

  public IncrementalHttpResponseParserIncrementalTest(String name, int[] lengths) {
  	this.name = name;
  	this.lengths = lengths.clone();
  }

  public String getName() {
    return name;
  }

  @Override
  public HttpResponse parse(byte[] data) throws Exception {
  	IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
  	int pos = 0;
  	for (int i = 0; i < lengths.length; i++) {
  		int len = Math.min(data.length-pos, lengths[i]);
  		int consumed = parser.parse(data, pos, len);
  		assertEquals(len, consumed);
  		pos += len;
  		if (pos == data.length) {
  		  break;
  		}
  		assertFalse(parser.isDone());
  	}
  	assertTrue(parser.isDone());
  	return parser.getResponse();
  }
}

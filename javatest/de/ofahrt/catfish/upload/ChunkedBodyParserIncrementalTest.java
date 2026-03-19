package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpRequest;
import info.adams.junit.NamedParameterized;
import info.adams.junit.NamedParameterized.Parameters;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import org.junit.runner.RunWith;

@RunWith(NamedParameterized.class)
public class ChunkedBodyParserIncrementalTest extends ChunkedBodyParserTest {

  @Parameters
  public static Collection<Object[]> data() {
    int[] oneByte = new int[257];
    Arrays.fill(oneByte, 0, 256, 1);
    oneByte[256] = Integer.MAX_VALUE;

    ArrayList<Object[]> result = new ArrayList<>();
    result.add(new Object[] {"No split", new int[] {Integer.MAX_VALUE}});
    result.add(new Object[] {"10-infty", new int[] {10, Integer.MAX_VALUE}});
    result.add(new Object[] {"4-4-4-4-4-4-infty", new int[] {4, 4, 4, 4, 4, 4, Integer.MAX_VALUE}});
    result.add(new Object[] {"256*1-infty", oneByte});
    return result;
  }

  private final int[] lengths;

  public ChunkedBodyParserIncrementalTest(String name, int[] lengths) {
    this.lengths = lengths.clone();
  }

  @Override
  protected byte[] parseBody(byte[] encodedInput) throws IOException {
    ChunkedBodyParser parser = new ChunkedBodyParser();
    int pos = 0;
    for (int i = 0; i < lengths.length; i++) {
      int len = Math.min(encodedInput.length - pos, lengths[i]);
      if (len == 0) break;
      int consumed = parser.parse(encodedInput, pos, len);
      assertTrue("Parser must consume at least one byte per call", consumed >= 1);
      pos += consumed;
      if (parser.isDone()) break;
    }
    assertTrue("Parser should be done after complete input", parser.isDone());
    HttpRequest.Body body = parser.getParsedBody(); // throws IOException if malformed
    return ((HttpRequest.InMemoryBody) body).toByteArray();
  }
}

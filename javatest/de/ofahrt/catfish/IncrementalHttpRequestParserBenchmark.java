package de.ofahrt.catfish;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@SuppressWarnings("NullAway") // JMH framework initializes fields
public class IncrementalHttpRequestParserBenchmark {

  private final IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
  private byte[] requestBytes;

  @Setup
  public void setup() {
    String request =
        "GET /index.html HTTP/1.1\r\n"
            + "Host: www.example.com\r\n"
            + "User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101"
            + " Firefox/128.0\r\n"
            + "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n"
            + "Accept-Language: en-US,en;q=0.5\r\n"
            + "Accept-Encoding: gzip, deflate, br\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n";
    requestBytes = request.getBytes(StandardCharsets.US_ASCII);
  }

  @Benchmark
  public int parseFullRequest() {
    parser.reset();
    return parser.parse(requestBytes);
  }
}

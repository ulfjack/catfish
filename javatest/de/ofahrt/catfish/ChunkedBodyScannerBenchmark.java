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
public class ChunkedBodyScannerBenchmark {

  private static final int CHUNK_DATA_SIZE = 4096;
  private static final int TCP_SEGMENT_SIZE = 65535;

  private final ChunkedBodyScanner scanner = new ChunkedBodyScanner();
  private byte[] chunkedBody;
  private int segmentCount;

  @Setup
  public void setup() {
    // 1 MB response body in 4 KB chunks, scanned in TCP-segment-sized calls.
    int totalData = 1024 * 1024;
    int chunkCount = totalData / CHUNK_DATA_SIZE;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < chunkCount; i++) {
      sb.append(Integer.toHexString(CHUNK_DATA_SIZE)).append("\r\n");
      sb.append(new String(new byte[CHUNK_DATA_SIZE], StandardCharsets.US_ASCII)).append("\r\n");
    }
    sb.append("0\r\n\r\n");
    chunkedBody = sb.toString().getBytes(StandardCharsets.US_ASCII);
    segmentCount = (chunkedBody.length + TCP_SEGMENT_SIZE - 1) / TCP_SEGMENT_SIZE;
  }

  @Benchmark
  public int scanChunkedBody() {
    scanner.reset();
    int total = 0;
    for (int i = 0; i < segmentCount; i++) {
      int off = i * TCP_SEGMENT_SIZE;
      int len = Math.min(TCP_SEGMENT_SIZE, chunkedBody.length - off);
      total += scanner.advance(chunkedBody, off, len);
    }
    return total;
  }
}

package de.ofahrt.catfish.internal.network;

import java.io.IOException;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.model.network.Connection;

/**
 * A single network pipeline stage. The first stage is connected directly to the network, and
 * each further stage is connected to the previous one. Each stage processes data from the
 * previous stage (or the network) and then passes the processed data on to the next state.
 * Usually, stages are connected through intermediate buffers, rather than using the same buffer.
 *
 * <p>Any exception thrown from any method results in the immediate termination of the
 * connection.
 */
public interface Stage {

  /**
   * Return value for {@link Stage#read} and {@link Stage#write} that controls how the current
   * stream or the current connection should be handled.
   */
  public enum ConnectionControl {
    /** Continue the current stream. */
    CONTINUE,

    /**
     * Continue the current stream, but stop processing the remaining input data. It is illegal
     * to return this from {@link Stage#write}.
     */
    NEED_MORE_DATA,

    /**
     * Pause the current stream; call {@link Pipeline#encourageReads} or {@link
     * Pipeline#encourageWrites} to resume the stream.
     */
    PAUSE,

    /**
     * Requests the input stream to be closed. It is illegal to return this from {@link
     * Stage#write}.
     */
    CLOSE_INPUT,

    /**
     * Requests the output stream to be closed after all outgoing data has been written. It is
     * illegal to return this from {@link Stage#read}.
     */
    CLOSE_OUTPUT_AFTER_FLUSH,

    /**
     * Closes the connection after all outgoing data has been written. It is illegal to return
     * this from {@link Stage#read}.
     */
    CLOSE_CONNECTION_AFTER_FLUSH,

    /**
     * Closes the connection immediately. This should only be called if a fatal error occurred that
     * makes it impossible to continue with the current connection. Any remaining data in the input
     * and output buffers will be discarded and no further calls to the pipeline should be made.
     */
    CLOSE_CONNECTION_IMMEDIATELY;
  }

  /** Controls whether the created pipeline expects to read or write data, or both. */
  public enum InitialConnectionState {
    READ_ONLY,
    WRITE_ONLY,
    READ_AND_WRITE;
  }

  /**
   * Called upon successful connection. This is usually the first method called on a Stage,
   * except for an outgoing connection that fails outright, in which case only {@link #close} is
   * called. Returns whether the connection should be open for reading, writing, or both.
   */
  InitialConnectionState connect(Connection connection);

  /**
   * Called when data is available from the network (or the previous stage). Each stage should
   * recursively call the next stage (except for the last one), and return how it wants the
   * connection to proceed.
   */
  ConnectionControl read() throws IOException;

  /**
   * Called when the input is closed, but only after all data in the input network buffer has
   * been processed.
   */
  void inputClosed() throws IOException;

  /**
   * Called to generate data for writing to the network (or the previous stage). Each stage
   * should recursively call the next stage (except for the last one), and return how it wants
   * the connection to proceed.
   *
   * <p>Note that there may be a delay between generating data for output and the data actually
   * being written to the network, if the socket is not yet ready for writing.
   */
  ConnectionControl write() throws IOException;

  /**
   * Called upon closure of the connection. In case of an outgoing connection, this may be called
   * without a previous call to {@link #connect} if the connection attempt fails outright. Most
   * importantly, stages should release any resources that are blocked on this connection,
   * including threads.
   */
  void close();
}
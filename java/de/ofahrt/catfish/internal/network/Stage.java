package de.ofahrt.catfish.internal.network;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.model.network.Connection;
import java.io.IOException;

/**
 * A single network pipeline stage. The first stage is connected directly to the network, and each
 * further stage is connected to the previous one. Each stage processes data from the previous stage
 * (or the network) and then passes the processed data on to the next state. Usually, stages are
 * connected through intermediate buffers, rather than using the same buffer.
 *
 * <p>The driver passes input and output {@link java.nio.ByteBuffer ByteBuffers} to {@link #read}
 * and {@link #write} in <em>read mode</em> (flipped). Stages must respect a specific buffer-mode
 * contract on those buffers — see the Javadoc on {@link #read} and {@link #write} for details.
 *
 * <p>Any exception thrown from any method results in the immediate termination of the connection.
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
     * Continue the current stream, but stop processing the remaining input data. It is illegal to
     * return this from {@link Stage#write}.
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
     * Closes the connection after all outgoing data has been written. It is illegal to return this
     * from {@link Stage#read}.
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
   * Called upon successful connection. This is usually the first method called on a Stage, except
   * for an outgoing connection that fails outright, in which case only {@link #close} is called.
   * Returns whether the connection should be open for reading, writing, or both.
   */
  InitialConnectionState connect(Connection connection);

  /**
   * Called when data is available from the network (or the previous stage). Each stage should
   * recursively call the next stage (except for the last one), and return how it wants the
   * connection to proceed.
   *
   * <p><b>Buffer contract.</b> The input buffer is supplied in <em>read mode</em> (flipped): {@code
   * position} points at the next unread byte and {@code limit} at the end of valid data. The stage
   * consumes bytes by advancing {@code position} toward {@code limit} (via {@code get()}, {@code
   * position(int)}, etc.) and MUST leave the buffer in read mode on return. Do NOT call {@code
   * clear()}, {@code compact()}, or {@code flip()} on the input buffer — the driver owns those
   * transitions.
   *
   * <p>A return of {@link ConnectionControl#CONTINUE CONTINUE} means "I made forward progress; call
   * me again." The driver detects forward progress by checking whether {@code
   * inputBuffer.remaining()} decreased. Returning {@code CONTINUE} 10 times in a row without
   * consuming any bytes is treated as a stage bug and throws {@link IllegalStateException}. If the
   * stage cannot make progress with the data currently buffered, it must return {@link
   * ConnectionControl#NEED_MORE_DATA NEED_MORE_DATA} instead, which tells the driver to stop
   * reading from the stage and wait for more network input. After EOF on the network side the
   * driver discards any leftover bytes and calls {@link #inputClosed} exactly once.
   */
  ConnectionControl read() throws IOException;

  /**
   * Called when the input is closed, but only after all data in the input network buffer has been
   * processed.
   */
  void inputClosed() throws IOException;

  /**
   * Called to generate data for writing to the network (or the previous stage). Each stage should
   * recursively call the next stage (except for the last one), and return how it wants the
   * connection to proceed.
   *
   * <p>Note that there may be a delay between generating data for output and the data actually
   * being written to the network, if the socket is not yet ready for writing.
   *
   * <p><b>Buffer contract.</b> The output buffer is supplied in <em>read mode</em> (flipped):
   * {@code [position, limit)} holds bytes already produced but not yet written to the network, and
   * {@code [limit, capacity)} is free space the stage may fill. The stage MUST leave the buffer in
   * read mode on return. The conventional pattern is:
   *
   * <pre>{@code
   * outputBuffer.compact();          // switch to write mode
   * // ... put bytes ...
   * outputBuffer.flip();             // back to read mode
   * return ConnectionControl.CONTINUE;
   * }</pre>
   *
   * <p>Forward progress is measured by {@code (capacity - limit)} shrinking — equivalently, by
   * {@code limit} growing. The driver's free-space accounting depends on {@code limit}, not {@code
   * position}, so a stage must never bump {@code position} past {@code limit} to "reserve" space;
   * the {@code compact()}/{@code flip()} pattern handles this automatically. Returning {@link
   * ConnectionControl#CONTINUE CONTINUE} without producing any bytes causes the driver to break out
   * of the write loop, so a stage that has nothing to send right now should return {@link
   * ConnectionControl#PAUSE PAUSE} instead.
   *
   * <p>{@link ConnectionControl#NEED_MORE_DATA NEED_MORE_DATA} and {@link
   * ConnectionControl#CLOSE_INPUT CLOSE_INPUT} are illegal from {@code write()} and throw {@link
   * IllegalStateException}.
   */
  ConnectionControl write() throws IOException;

  /**
   * Called upon closure of the connection. In case of an outgoing connection, this may be called
   * without a previous call to {@link #connect} if the connection attempt fails outright. Most
   * importantly, stages should release any resources that are blocked on this connection, including
   * threads.
   */
  void close();
}

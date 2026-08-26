package io.github.giovanicaprison.matching.flow;

import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * An encoded command log, resident in one buffer.
 *
 * <p>One buffer and an index rather than a list of messages, so that replaying it touches the same
 * memory in the same order every time and the harness never allocates inside a measurement.
 *
 * <p>A log can be written to a file and read back, which is how a Java run and a C++ run are fed
 * the same bytes. Only one generator exists, and neither language owns it.
 *
 * <p>Nothing in a log depends on what an engine did with it. A cancel names its target by the
 * client order id the order was entered with, so the bytes go to any implementation unaltered and
 * the driver does no work per command beyond publishing it.
 */
public final class CommandLog {

  private static final byte[] MAGIC = "MEFLOW01".getBytes(StandardCharsets.UTF_8);

  private final DirectBuffer buffer;
  private final int[] offsets;
  private final int[] lengths;
  private final int count;
  private final int measuredFrom;

  /**
   * A log from whoever encoded one: the generator here, a file read back, or a real session's feed
   * converted into commands. The buffer and the index are taken as given (P-14).
   */
  public CommandLog(
      final DirectBuffer buffer,
      final int[] offsets,
      final int[] lengths,
      final int count,
      final int measuredFrom) {
    this.buffer = buffer;
    this.offsets = offsets;
    this.lengths = lengths;
    this.count = count;
    this.measuredFrom = measuredFrom;
  }

  /** How many commands the log holds, warm-up included. */
  public int count() {
    return count;
  }

  /**
   * The first command of the measured region.
   *
   * <p>Everything before it brings the book to size, and measuring it would report the cost of
   * filling an empty book rather than of running a full one.
   */
  public int measuredFrom() {
    return measuredFrom;
  }

  public DirectBuffer buffer() {
    return buffer;
  }

  public int offset(final int command) {
    return offsets[command];
  }

  public int length(final int command) {
    return lengths[command];
  }

  /**
   * What the command at this position is, read from its own header rather than from a side table
   * that could disagree with the messages. Tests ask this; a measurement never does, so nothing is
   * decoded or held for it on the measured path.
   */
  public int templateId(final int command) {
    return new MessageHeaderDecoder().wrap(buffer, offsets[command]).templateId();
  }

  public void writeTo(final Path file) {
    final ByteBuffer out =
        ByteBuffer.allocate(MAGIC.length + Integer.BYTES * (2 + count) + totalBytes())
            .order(ByteOrder.LITTLE_ENDIAN);
    out.put(MAGIC).putInt(count).putInt(measuredFrom);
    final byte[] message = new byte[longestCommand()];
    for (int command = 0; command < count; command++) {
      buffer.getBytes(offsets[command], message, 0, lengths[command]);
      out.putInt(lengths[command]).put(message, 0, lengths[command]);
    }
    try {
      Files.write(file, out.array());
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot write the log to " + file, e);
    }
  }

  public static CommandLog readFrom(final Path file) {
    final byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot read the log at " + file, e);
    }
    final ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    final byte[] magic = new byte[MAGIC.length];
    in.get(magic);
    if (!Arrays.equals(magic, MAGIC)) {
      throw new IllegalArgumentException(file + " is not a command log");
    }
    final int count = in.getInt();
    final int measuredFrom = in.getInt();
    final int[] offsets = new int[count];
    final int[] lengths = new int[count];
    final MutableDirectBuffer buffer = new ExpandableArrayBuffer(bytes.length);
    int at = 0;
    for (int command = 0; command < count; command++) {
      final int length = in.getInt();
      final byte[] message = new byte[length];
      in.get(message);
      buffer.putBytes(at, message);
      offsets[command] = at;
      lengths[command] = length;
      at += length;
    }
    return new CommandLog(new UnsafeBuffer(buffer, 0, at), offsets, lengths, count, measuredFrom);
  }

  private int totalBytes() {
    int total = 0;
    for (int command = 0; command < count; command++) {
      total += lengths[command];
    }
    return total;
  }

  private int longestCommand() {
    int longest = 0;
    for (int command = 0; command < count; command++) {
      longest = Math.max(longest, lengths[command]);
    }
    return longest;
  }
}

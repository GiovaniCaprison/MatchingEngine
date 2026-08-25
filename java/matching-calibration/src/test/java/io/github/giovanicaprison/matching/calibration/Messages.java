package io.github.giovanicaprison.matching.calibration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Builds a stream of length-prefixed ITCH messages, written out by hand from the published layout
 * rather than captured from a file. Shared by the reader's test and the replay's, which want the
 * same bytes for different questions.
 */
final class Messages {

  private final ByteArrayOutputStream all = new ByteArrayOutputStream();

  void add(final char type, final java.util.function.Consumer<Writer> fields) {
    final Writer writer = new Writer(type);
    fields.accept(writer);
    final byte[] body = writer.bytes();
    all.write(body.length >> 8);
    all.write(body.length & 0xFF);
    all.write(body, 0, body.length);
  }

  ByteArrayInputStream stream() {
    return new ByteArrayInputStream(all.toByteArray());
  }

  static final class Writer {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final DataOutputStream data = new DataOutputStream(out);

    Writer(final char type) {
      write(() -> data.write(type));
    }

    Writer locate(final int locate) {
      return write(() -> data.writeShort(locate));
    }

    /** Tracking number then the six byte timestamp, which always follow the locate. */
    Writer timestamp(final long nanos) {
      return write(
          () -> {
            data.writeShort(0);
            for (int shift = 40; shift >= 0; shift -= 8) {
              data.write((int) (nanos >> shift & 0xFF));
            }
          });
    }

    Writer one(final char value) {
      return write(() -> data.write(value));
    }

    Writer four(final long value) {
      return write(() -> data.writeInt((int) value));
    }

    Writer eight(final long value) {
      return write(() -> data.writeLong(value));
    }

    Writer stock(final String symbol) {
      return write(
          () -> {
            final byte[] padded = new byte[8];
            java.util.Arrays.fill(padded, (byte) ' ');
            final byte[] bytes = symbol.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            data.write(padded);
          });
    }

    Writer pad(final int bytes) {
      return write(() -> data.write(new byte[bytes]));
    }

    byte[] bytes() {
      return out.toByteArray();
    }

    private Writer write(final Written written) {
      try {
        written.write();
      } catch (final IOException e) {
        throw new AssertionError("writing to memory cannot fail", e);
      }
      return this;
    }

    private interface Written {
      void write() throws IOException;
    }
  }
}

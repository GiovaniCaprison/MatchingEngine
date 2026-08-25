package io.github.giovanicaprison.matching.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Field offsets, against messages built by hand from the specification.
 *
 * <p>A reader whose offsets are one byte out still reads: prices come back plausible and quantities
 * come back wrong, and nothing says so. So the bytes here are written out from the published layout
 * rather than captured from a file, which is the only way the two can disagree.
 */
class ItchTest {

  @Test
  @DisplayName("an add order is read at the offsets the specification gives")
  void an_add_order_is_read_correctly() throws IOException {
    final Messages messages = new Messages();
    messages.add(
        'A',
        writer -> {
          writer.locate(42).timestamp(34_200_000_000_000L);
          writer.eight(9_001L).one('B').four(500).stock("AAPL").four(1_002_500);
        });

    final Itch.Message read = new Itch(messages.stream()).next();

    assertThat(read.type).isEqualTo('A');
    assertThat(read.stockLocate).isEqualTo(42);
    assertThat(read.timestamp).isEqualTo(34_200_000_000_000L);
    assertThat(read.orderReference).isEqualTo(9_001L);
    assertThat(read.side).isEqualTo('B');
    assertThat(read.shares).isEqualTo(500);
    assertThat(read.stock).isEqualTo("AAPL");
    assertThat(read.price).isEqualTo(1_002_500);
  }

  @Test
  @DisplayName("a replace carries both references, so a lifetime can be followed across it")
  void a_replace_is_read_correctly() throws IOException {
    final Messages messages = new Messages();
    messages.add(
        'U',
        writer -> {
          writer.locate(7).timestamp(1_000L);
          writer.eight(111L).eight(222L).four(300).four(999_500);
        });

    final Itch.Message read = new Itch(messages.stream()).next();

    assertThat(read.orderReference).isEqualTo(111L);
    assertThat(read.newOrderReference).isEqualTo(222L);
    assertThat(read.shares).isEqualTo(300);
    assertThat(read.price).isEqualTo(999_500);
  }

  @Test
  @DisplayName("a cancel, a delete and an execution are told apart")
  void the_removal_messages_are_read_correctly() throws IOException {
    final Messages messages = new Messages();
    messages.add('X', writer -> writer.locate(1).timestamp(5L).eight(77L).four(150));
    messages.add('D', writer -> writer.locate(1).timestamp(6L).eight(88L));
    messages.add('E', writer -> writer.locate(1).timestamp(7L).eight(99L).four(250).eight(5L));

    final Itch itch = new Itch(messages.stream());

    final Itch.Message cancel = itch.next();
    assertThat(cancel.type).isEqualTo('X');
    assertThat(cancel.orderReference).isEqualTo(77L);
    assertThat(cancel.shares).isEqualTo(150);

    final Itch.Message delete = itch.next();
    assertThat(delete.type).isEqualTo('D');
    assertThat(delete.orderReference).isEqualTo(88L);
    assertThat(delete.shares).as("a delete takes the whole order and names no quantity").isZero();

    final Itch.Message executed = itch.next();
    assertThat(executed.type).isEqualTo('E');
    assertThat(executed.orderReference).isEqualTo(99L);
    assertThat(executed.shares).isEqualTo(250);
  }

  @Test
  @DisplayName("a stock directory gives the locate a symbol")
  void a_directory_entry_names_a_symbol() throws IOException {
    final Messages messages = new Messages();
    messages.add(
        'R',
        writer -> {
          writer.locate(1_234).timestamp(9L).stock("MSFT");
          writer.pad(39 - 19);
        });

    final Itch.Message read = new Itch(messages.stream()).next();

    assertThat(read.stockLocate).isEqualTo(1_234);
    assertThat(read.stock).isEqualTo("MSFT");
  }

  @Test
  @DisplayName("a message this does not understand is stepped over, not guessed at")
  void an_unknown_message_is_skipped() throws IOException {
    final Messages messages = new Messages();
    messages.add('Z', writer -> writer.locate(1).timestamp(1L).pad(20));
    messages.add('D', writer -> writer.locate(2).timestamp(2L).eight(55L));

    final Itch itch = new Itch(messages.stream());

    assertThat(itch.next().type).isEqualTo('Z');
    final Itch.Message after = itch.next();
    assertThat(after.type).as("the reader stayed in step").isEqualTo('D');
    assertThat(after.orderReference).isEqualTo(55L);
    assertThat(itch.next()).isNull();
  }

  @Test
  @DisplayName("a length no message can have is refused rather than read")
  void a_wrong_length_is_refused() {
    final byte[] nonsense = {(byte) 0xFF, (byte) 0xFF, 1, 2, 3};

    assertThatExceptionOfType(IOException.class)
        .isThrownBy(() -> new Itch(new ByteArrayInputStream(nonsense)).next())
        .withMessageContaining("is not ITCH 5.0");
  }

  /**
   * Builds a stream of length-prefixed messages, so the layout under test is written out by hand.
   */
  private static final class Messages {

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
  }

  private static final class Writer {

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

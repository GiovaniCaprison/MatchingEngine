package io.github.giovanicaprison.matching.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
}

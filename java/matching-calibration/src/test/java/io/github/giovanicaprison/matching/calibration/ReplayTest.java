package io.github.giovanicaprison.matching.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.protocol.CancelOrderDecoder;
import io.github.giovanicaprison.matching.protocol.InstrumentDefinitionDecoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import io.github.giovanicaprison.matching.protocol.NewOrderDecoder;
import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.ReplaceOrderDecoder;
import io.github.giovanicaprison.matching.protocol.SessionStateChangeDecoder;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The conversion rules, against bytes built by hand.
 *
 * <p>What matters here is the reconstruction: the aggressor a feed never shows arrives as one
 * immediate-or-cancel at the resting price, a partial cancel becomes the same-price replace that
 * keeps queue position, and a replace chain keeps the name it was entered under, since our protocol
 * amends by client order id (FR-4.8).
 */
class ReplayTest {

  private static final long IN_WINDOW = 35_000_000_000_000L;

  @Test
  @DisplayName("a session becomes the commands that would have caused it")
  void the_feed_converts_to_commands() throws IOException {
    final Messages messages = new Messages();
    messages.add('R', writer -> writer.locate(7).timestamp(1L).stock("AAPL").pad(39 - 19));
    // An add, thirty shares executed against it, twenty cancelled down, then deleted.
    messages.add(
        'A',
        writer ->
            writer
                .locate(7)
                .timestamp(IN_WINDOW)
                .eight(9001)
                .one('B')
                .four(100)
                .stock("AAPL")
                .four(1_000_000));
    messages.add(
        'E', writer -> writer.locate(7).timestamp(IN_WINDOW).eight(9001).four(30).eight(1));
    messages.add('X', writer -> writer.locate(7).timestamp(IN_WINDOW).eight(9001).four(20));
    messages.add('D', writer -> writer.locate(7).timestamp(IN_WINDOW).eight(9001));
    // A second order replaced to a new reference, then deleted under it.
    messages.add(
        'A',
        writer ->
            writer
                .locate(7)
                .timestamp(IN_WINDOW)
                .eight(9002)
                .one('S')
                .four(50)
                .stock("AAPL")
                .four(1_000_500));
    messages.add(
        'U',
        writer ->
            writer.locate(7).timestamp(IN_WINDOW).eight(9002).eight(9003).four(40).four(1_000_600));
    messages.add('D', writer -> writer.locate(7).timestamp(IN_WINDOW).eight(9003));

    final CommandLog log = convert(messages);

    final Walk walk = new Walk(log);
    walk.instrument();
    walk.session();
    walk.newOrder(9001, 1, Side.BUY, TimeInForce.GOOD_TILL_CANCEL, 1_000_000, 100);
    // The synthesized aggressor: the other side, at the resting price, immediate or cancel.
    walk.newOrder(1, 2, Side.SELL, TimeInForce.IMMEDIATE_OR_CANCEL, 1_000_000, 30);
    // The partial cancel keeps queue position: same price, total = remaining 50 + executed 30.
    walk.replace(9001, 80, 1_000_000);
    walk.cancel(9001);
    walk.newOrder(9002, 1, Side.SELL, TimeInForce.GOOD_TILL_CANCEL, 1_000_500, 50);
    // The replace keeps the original name, and the delete under the new reference finds it too.
    walk.replace(9002, 40, 1_000_600);
    walk.cancel(9002);
    assertThat(walk.walked()).isEqualTo(log.count());
    assertThat(log.measuredFrom()).as("everything sits inside the window").isZero();
  }

  @Test
  @DisplayName("everything before the window converts as the warm-up that builds the book")
  void the_pre_open_becomes_warm_up() throws IOException {
    final Messages messages = new Messages();
    messages.add('R', writer -> writer.locate(7).timestamp(1L).stock("AAPL").pad(39 - 19));
    messages.add(
        'A',
        writer ->
            writer
                .locate(7)
                .timestamp(1_000_000_000L)
                .eight(9001)
                .one('B')
                .four(100)
                .stock("AAPL")
                .four(1_000_000));
    messages.add(
        'A',
        writer ->
            writer
                .locate(7)
                .timestamp(IN_WINDOW)
                .eight(9002)
                .one('S')
                .four(100)
                .stock("AAPL")
                .four(1_000_500));

    final CommandLog log = convert(messages);

    assertThat(log.count()).isEqualTo(4);
    assertThat(log.measuredFrom())
        .as("the definition, the session and the early add are warm-up; the in-window add is not")
        .isEqualTo(3);
  }

  private static CommandLog convert(final Messages messages) throws IOException {
    return new Replay("AAPL", Long.MAX_VALUE, 9 * 3600 + 30 * 60, 100, 1)
        .convert(messages.stream());
  }

  /** Walks the converted log one command at a time, asserting each is what the feed implied. */
  private static final class Walk {

    private final CommandLog log;
    private final MessageHeaderDecoder header = new MessageHeaderDecoder();
    private int at;

    Walk(final CommandLog log) {
      this.log = log;
    }

    int walked() {
      return at;
    }

    void instrument() {
      final InstrumentDefinitionDecoder decoder = new InstrumentDefinitionDecoder();
      wrap(InstrumentDefinitionDecoder.TEMPLATE_ID);
      decoder.wrap(log.buffer(), body(), header.blockLength(), header.version());
      assertThat(decoder.tickSize()).isEqualTo(100);
      assertThat(decoder.openingReference()).isEqualTo(1_000_000);
      at++;
    }

    void session() {
      wrap(SessionStateChangeDecoder.TEMPLATE_ID);
      at++;
    }

    void newOrder(
        final long clientOrderId,
        final int participant,
        final Side side,
        final TimeInForce timeInForce,
        final long price,
        final long quantity) {
      final NewOrderDecoder decoder = new NewOrderDecoder();
      wrap(NewOrderDecoder.TEMPLATE_ID);
      decoder.wrap(log.buffer(), body(), header.blockLength(), header.version());
      assertThat(decoder.clientOrderId()).isEqualTo(clientOrderId);
      assertThat((int) decoder.participantId()).isEqualTo(participant);
      assertThat(decoder.side()).isEqualTo(side);
      assertThat(decoder.pricing()).isEqualTo(PricingInstruction.LIMIT);
      assertThat(decoder.timeInForce()).isEqualTo(timeInForce);
      assertThat(decoder.price()).isEqualTo(price);
      assertThat(decoder.quantity()).isEqualTo(quantity);
      at++;
    }

    void replace(final long clientOrderId, final long quantity, final long price) {
      final ReplaceOrderDecoder decoder = new ReplaceOrderDecoder();
      wrap(ReplaceOrderDecoder.TEMPLATE_ID);
      decoder.wrap(log.buffer(), body(), header.blockLength(), header.version());
      assertThat(decoder.clientOrderId()).isEqualTo(clientOrderId);
      assertThat(decoder.quantity()).isEqualTo(quantity);
      assertThat(decoder.price()).isEqualTo(price);
      at++;
    }

    void cancel(final long clientOrderId) {
      final CancelOrderDecoder decoder = new CancelOrderDecoder();
      wrap(CancelOrderDecoder.TEMPLATE_ID);
      decoder.wrap(log.buffer(), body(), header.blockLength(), header.version());
      assertThat(decoder.clientOrderId()).isEqualTo(clientOrderId);
      at++;
    }

    private void wrap(final int expectedTemplate) {
      header.wrap(log.buffer(), log.offset(at));
      assertThat(header.templateId()).as("command %d", at).isEqualTo(expectedTemplate);
    }

    private int body() {
      return log.offset(at) + MessageHeaderDecoder.ENCODED_LENGTH;
    }
  }
}

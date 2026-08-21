package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.api.EventSink;
import io.github.giovanicaprison.matching.api.Instrument;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.naive.NaiveMatchingEngineFactory;
import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import io.github.giovanicaprison.matching.protocol.OrderExecutedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRejectedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRemovedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRestedDecoder;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generator has to be right or every number taken with it is meaningless, and it can be wrong
 * quietly. A passive flow that crosses, or a warm-up that leaves a smaller book than it claims,
 * would not fail anything else: the benchmark would run happily and measure the wrong thing.
 */
class LogGeneratorTest {

  private static final Instrument INSTRUMENT = new Instrument(1, 5L, 1L, 1L, 1_000_000_000L, 4);
  private static final int COUNT = 500;

  private final LogGenerator generator = new LogGenerator(FlowParameters.standard(42L));
  private final Tally tally = new Tally();
  private final MatchingEngine engine = new NaiveMatchingEngineFactory().create(INSTRUMENT, tally);

  @Test
  @DisplayName("a warm-up log rests every order and trades none of them")
  void passive_orders_never_cross() {
    replay(generator.passiveOrders(COUNT, 1L));

    assertThat(tally.executed).as("executions in a passive warm-up").isZero();
    assertThat(tally.rejected).as("refusals").isZero();
    assertThat(tally.rested).as("orders left resting").isEqualTo(COUNT);
  }

  @Test
  @DisplayName("a crossing log actually crosses, and takes one resting order each")
  void crossing_orders_execute() {
    replay(generator.passiveOrders(COUNT, 1L));
    tally.reset();

    replay(generator.crossingOrders(COUNT / 2, COUNT + 1L));

    // Each crossing order is minimum size against a book of larger orders, so it fills once and
    // leaves nothing resting. If it stopped crossing, submitCrossing would silently become a
    // measurement of submitResting.
    assertThat(tally.executed).as("executions").isEqualTo(COUNT / 2);
    assertThat(tally.rested).as("crossing orders that rested").isZero();
    assertThat(tally.rejected).as("refusals").isZero();
  }

  @Test
  @DisplayName("a cancel log removes real orders rather than reporting unknown ones")
  void cancels_hit_live_orders() {
    replay(generator.passiveOrders(COUNT, 1L));
    tally.reset();

    replay(generator.cancels(COUNT / 2, 1L, COUNT + 1L));

    assertThat(tally.removed).as("orders removed").isEqualTo(COUNT / 2);
    assertThat(tally.rejected).as("cancels that found nothing").isZero();
  }

  @Test
  @DisplayName("the same seed gives the same log")
  void reproducible() {
    final CommandLog first = new LogGenerator(FlowParameters.standard(7L)).passiveOrders(COUNT, 1L);
    final CommandLog second =
        new LogGenerator(FlowParameters.standard(7L)).passiveOrders(COUNT, 1L);

    assertThat(second.count()).isEqualTo(first.count());
    for (int i = 0; i < first.count(); i++) {
      final byte[] a = new byte[first.length(i)];
      final byte[] b = new byte[second.length(i)];
      first.buffer().getBytes(first.offset(i), a);
      second.buffer().getBytes(second.offset(i), b);
      assertThat(b).as("command %d", i).isEqualTo(a);
    }
  }

  private void replay(final CommandLog log) {
    for (int i = 0; i < log.count(); i++) {
      engine.onCommand(log.buffer(), log.offset(i), log.length(i));
    }
  }

  /** Counts events by kind, so a test can say what the flow did rather than how long it took. */
  private static final class Tally implements EventSink {

    private final MessageHeaderDecoder header = new MessageHeaderDecoder();

    private int rested;
    private int executed;
    private int removed;
    private int rejected;

    private void reset() {
      rested = 0;
      executed = 0;
      removed = 0;
      rejected = 0;
    }

    @Override
    public void onEvent(final DirectBuffer buffer, final int offset, final int length) {
      header.wrap(buffer, offset);
      switch (header.templateId()) {
        case OrderRestedDecoder.TEMPLATE_ID -> rested++;
        case OrderExecutedDecoder.TEMPLATE_ID -> executed++;
        case OrderRemovedDecoder.TEMPLATE_ID -> removed++;
        case OrderRejectedDecoder.TEMPLATE_ID -> rejected++;
        default -> {
          // Accepted events carry no information a flow check needs.
        }
      }
    }
  }
}

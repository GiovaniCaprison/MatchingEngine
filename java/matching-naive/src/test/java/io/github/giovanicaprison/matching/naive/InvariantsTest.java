package io.github.giovanicaprison.matching.naive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.conformance.ConsumerBook;
import io.github.giovanicaprison.matching.conformance.FlowReplay;
import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import io.github.giovanicaprison.matching.protocol.Side;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What has to be true of the structures after any sequence at all.
 *
 * <p>Inside the package, because the structures are. These catch drift: every operation correct and
 * the book slowly wrong, which no fixture finds, because a fixture only checks the states somebody
 * thought of. The flow is generated with call phases, so an uncrossing happens every few thousand
 * commands and the features meet each other there.
 *
 * <p>Several seeds rather than one. A property that holds for a single sequence is a fixture with
 * extra steps.
 */
class InvariantsTest {

  @DisplayName("NFR-3.3 NFR-3.4 FR-8.2 the structures hold together across generated flow")
  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {1, 2, 3, 20260825})
  void nothing_drifts(final long seed) {
    final CommandLog log = FlowGenerator.generate(FlowParameters.withAuctions(seed, 40_000, 2_000));
    final Engines engines = new Engines();

    final long events =
        FlowReplay.replay(
            log, engines, (command, rebuilt) -> check(engines.engine, rebuilt, command));

    assertThat(events).as("a flow this size has to make the engine say something").isPositive();
  }

  private static void check(
      final NaiveEngine engine, final ConsumerBook rebuilt, final int command) {
    final String where = "after command " + command;
    final List<Order> resting = engine.resting();
    final List<Order> waiting = engine.waiting();

    // (NFR-3.2) One order, one home. An order in both structures would be liquidity and a condition
    // at the same time, and whichever one removed it would leave the other holding a ghost.
    final Set<Long> ids = new HashSet<>();
    for (final Order order : resting) {
      assertThat(ids.add(order.id())).as("%s: %d is resting twice", where, order.id()).isTrue();
    }
    for (final Order stop : waiting) {
      assertThat(ids.add(stop.id())).as("%s: %d is in both books", where, stop.id()).isTrue();
    }

    for (final Order order : resting) {
      assertThat(order.remaining()).as("%s: %d rests on nothing", where, order.id()).isPositive();
      assertThat(order.displayed())
          .as("%s: %d shows nothing, so nothing can reach it", where, order.id())
          .isPositive();
      assertThat(order.displayed())
          .as("%s: %d shows more than it has", where, order.id())
          .isLessThanOrEqualTo(order.remaining());
      assertThat(order.stop()).as("%s: %d is a stop in the book", where, order.id()).isFalse();
    }

    // (NFR-3.3) The trigger book holds the stops that have not fired, which means none of them have
    // been reached. One that had been reached and stayed is a stop that will never fire.
    for (final Order stop : waiting) {
      assertThat(stop.stop()).as("%s: %d waits without a trigger", where, stop.id()).isTrue();
      assertThat(stop.remaining()).as("%s: %d waits on nothing", where, stop.id()).isPositive();
      final long price = engine.lastExecutedPrice();
      if (price == 0) {
        continue;
      }
      final boolean reached =
          stop.side() == Side.BUY ? price >= stop.triggerPrice() : price <= stop.triggerPrice();
      assertThat(reached).as("%s: %d should have fired at %d", where, stop.id(), price).isFalse();
    }

    // (FR-8.2) And the book a consumer is holding is the book the engine is holding, over flow
    // nobody
    // wrote down rather than only over the fixtures.
    assertThat(Engines.visible(resting))
        .as("%s: the two books have parted company", where)
        .isEqualTo(rebuilt.entries());
  }
}

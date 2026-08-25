package io.github.giovanicaprison.matching.pooled;

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
 * What has to be true of the pooled structures after any sequence at all.
 *
 * <p>The bookkeeping the rung below armed these against can now drift one more way: a level or an
 * order that came back from a pool carries whatever its last life left in it, so a stale link or a
 * stale total looks exactly like drift. The same checks catch both, and the id checks below would
 * see an order that is somehow resting twice. Checked after every command over generated flow,
 * because drift is the failure where every operation looks correct and the structure is slowly
 * wrong.
 */
class InvariantsTest {

  @DisplayName("NFR-3.1 NFR-3.2 the bookkeeping never parts from the book across generated flow")
  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {1, 2, 3, 20260826})
  void nothing_drifts(final long seed) {
    final CommandLog log = FlowGenerator.generate(FlowParameters.withAuctions(seed, 40_000, 2_000));
    final Engines engines = new Engines();

    final long events =
        FlowReplay.replay(
            log, engines, (command, rebuilt) -> check(engines.engine, rebuilt, command));

    assertThat(events).as("a flow this size has to make the engine say something").isPositive();
  }

  private static void check(
      final PooledEngine engine, final ConsumerBook rebuilt, final int command) {
    final String where = "after command " + command;
    final Book book = engine.book();
    final List<Order> resting = engine.resting();

    // (NFR-3.1) The cached aggregate at every price equals the sum of the orders under it. This is
    // the number a fast path would quote, so the moment it drifts every answer built on it lies.
    // (NFR-3.2) And no empty level survives, on either side.
    long queued = 0;
    for (final Side side : Side.values()) {
      if (side == Side.NULL_VAL) {
        continue;
      }
      for (final Book.Level level : book.levels(side)) {
        final List<Order> forward = level.queue();
        assertThat(forward)
            .as("%s: an empty level at %d survived", where, level.price())
            .isNotEmpty();
        // The chain read backwards tells the same story, or an unlink half-happened somewhere.
        assertThat(level.queueReversed().reversed())
            .as("%s: level %d reads differently backwards", where, level.price())
            .isEqualTo(forward);
        long sum = 0;
        for (final Order order : forward) {
          sum += order.displayed();
          assertThat(order.side())
              .as("%s: %d rests on the wrong side", where, order.id())
              .isEqualTo(side);
          assertThat(order.price())
              .as("%s: %d rests at the wrong price", where, order.id())
              .isEqualTo(level.price());
          queued++;
        }
        assertThat(level.displayed())
            .as(
                "%s: level %d says %d displayed and holds %d",
                where, level.price(), level.displayed(), sum)
            .isEqualTo(sum);
      }
    }

    // (NFR-3.2) No unreferenced order remains: the index holds exactly what the queues hold.
    assertThat(resting.size())
        .as("%s: the index and the queues disagree about who is resting", where)
        .isEqualTo((int) queued);
    final Set<Long> ids = new HashSet<>();
    for (final Order order : resting) {
      assertThat(ids.add(order.id())).as("%s: %d is indexed twice", where, order.id()).isTrue();
    }
    for (final Order stop : engine.waiting()) {
      assertThat(ids.add(stop.id())).as("%s: %d is in both books", where, stop.id()).isTrue();
    }

    // The book a consumer holds is the book the engine holds, at this layout as at the last.
    assertThat(Engines.visible(resting))
        .as("%s: the two books have parted company", where)
        .isEqualTo(rebuilt.entries());
  }
}

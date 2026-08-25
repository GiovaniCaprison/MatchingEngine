package io.github.giovanicaprison.matching.flyweight;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.conformance.ConsumerBook;
import io.github.giovanicaprison.matching.conformance.FlowReplay;
import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What has to be true of the flyweight structures after any sequence at all.
 *
 * <p>This rung adds a new way for the bookkeeping to lie: the occupancy bitmap and the cached best
 * rank are summaries of the ladder, and a summary that parts from its ladder answers best-price
 * questions from prices nobody is at, which is this rung's version of drift. So beyond the checks
 * every rung carries, the queues each occupied bit names are walked, every resting order's tick is
 * held against the bitmap, the cached best is held against a fresh search, and at the end of the
 * flow the whole ladder is swept tick by tick (NFR-3.1, NFR-3.2).
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
    sweep(engines.engine);
  }

  private static void check(
      final FlyweightEngine engine, final ConsumerBook rebuilt, final int command) {
    final String where = "after command " + command;
    final Book book = engine.book();
    final Slab slab = engine.slab();
    final List<Integer> resting = book.restingSlots();

    long queued = 0;
    for (int side = 0; side <= 1; side++) {
      // (NFR-3.2) Every tick the bitmap names holds a queue, so a set bit never lies, and the
      // cached best agrees with a fresh search of the summaries.
      assertThat(book.cachedBestRank(side))
          .as("%s: side %d caches a best the bitmap does not name", where, side)
          .isEqualTo(book.searchedBestRank(side));
      for (final int tick : book.occupiedTicks(side)) {
        final List<Integer> forward = book.queueAt(side, tick);
        assertThat(forward)
            .as("%s: the bitmap names tick %d and nobody is there", where, tick)
            .isNotEmpty();
        // The chain read backwards tells the same story, or an unlink half-happened somewhere.
        assertThat(book.queueReversedAt(side, tick).reversed())
            .as("%s: tick %d reads differently backwards", where, tick)
            .isEqualTo(forward);
        long shown = 0;
        long left = 0;
        for (final int slot : forward) {
          shown += slab.displayed(slot);
          left += slab.remaining(slot);
          assertThat(slab.side(slot))
              .as("%s: %d rests on the wrong side", where, slab.id(slot))
              .isEqualTo(side);
          assertThat(slab.tick(slot))
              .as("%s: %d rests at the wrong tick", where, slab.id(slot))
              .isEqualTo(tick);
          queued++;
        }
        // (NFR-3.1) The cached totals at every price equal the sums of the orders under them.
        assertThat(book.displayedTotalAt(side, tick))
            .as(
                "%s: tick %d displays %d and holds %d",
                where, tick, book.displayedTotalAt(side, tick), shown)
            .isEqualTo(shown);
        assertThat(book.remainingTotalAt(side, tick))
            .as(
                "%s: tick %d says %d remaining and holds %d",
                where, tick, book.remainingTotalAt(side, tick), left)
            .isEqualTo(left);
      }
    }

    // (NFR-3.2) No unreferenced order remains: the index holds exactly what the queues hold, and
    // every indexed order's tick is a bit the bitmap set, so a cleared bit never lies either.
    assertThat(resting.size())
        .as("%s: the index and the queues disagree about who is resting", where)
        .isEqualTo((int) queued);
    final Set<Long> ids = new HashSet<>();
    for (final int slot : resting) {
      assertThat(book.occupiedBit(slab.side(slot), slab.tick(slot)))
          .as("%s: %d rests at a tick the bitmap calls empty", where, slab.id(slot))
          .isTrue();
      assertThat(ids.add(slab.id(slot)))
          .as("%s: %d is indexed twice", where, slab.id(slot))
          .isTrue();
    }
    for (final int stop : engine.waiting()) {
      assertThat(ids.add(slab.id(stop)))
          .as("%s: %d is in both books", where, slab.id(stop))
          .isTrue();
    }

    // The book a consumer holds is the book the engine holds, at this layout as at the last.
    assertThat(Engines.visible(engine))
        .as("%s: the two books have parted company", where)
        .isEqualTo(rebuilt.entries());
  }

  /** The whole ladder, tick by tick: an unoccupied level is empty, unlinked and holds nothing. */
  private static void sweep(final FlyweightEngine engine) {
    final Book book = engine.book();
    for (int side = 0; side <= 1; side++) {
      for (int tick = 0; tick < book.tickCount(); tick++) {
        if (book.occupiedBit(side, tick)) {
          assertThat(book.headSlotAt(side, tick))
              .as("sweep: the bitmap names tick %d on side %d and nobody is there", tick, side)
              .isNotEqualTo(0);
        } else {
          assertThat(book.headSlotAt(side, tick))
              .as("sweep: tick %d on side %d is queued behind a cleared bit", tick, side)
              .isEqualTo(0);
          assertThat(book.displayedTotalAt(side, tick))
              .as("sweep: an empty tick %d on side %d still displays quantity", tick, side)
              .isZero();
          assertThat(book.remainingTotalAt(side, tick))
              .as("sweep: an empty tick %d on side %d still holds quantity", tick, side)
              .isZero();
        }
      }
    }
  }
}

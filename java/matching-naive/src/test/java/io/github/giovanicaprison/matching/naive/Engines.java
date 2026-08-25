package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import io.github.giovanicaprison.matching.conformance.ConsumerBook;
import java.util.List;

/**
 * A factory that keeps the engine it built, which is the only way to see inside it afterwards.
 *
 * <p>Shared by the tests in this package that compare the engine's own book to the one the feed
 * describes, along with that comparison's view of it: queue order, and displayed quantity only.
 * Queue order rather than list order, since a replenished tranche goes to the back of its price
 * without moving in any list, and two books that agree on quantities while disagreeing on position
 * would allocate differently and still pass a comparison that only added up.
 */
final class Engines implements MatchingEngineFactory {

  NaiveEngine engine;

  @Override
  public MatchingEngine create(final EventPublisher events) {
    engine = new NaiveEngine(events);
    return engine;
  }

  /** The engine's book as the feed describes it. */
  static List<ConsumerBook.Entry> visible(final List<Order> resting) {
    return resting.stream()
        .sorted(Order.BY_ARRIVAL)
        .map(
            order ->
                new ConsumerBook.Entry(order.id(), order.side(), order.price(), order.displayed()))
        .toList();
  }
}

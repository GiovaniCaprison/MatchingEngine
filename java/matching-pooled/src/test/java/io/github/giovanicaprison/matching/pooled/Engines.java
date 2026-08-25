package io.github.giovanicaprison.matching.pooled;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import io.github.giovanicaprison.matching.conformance.ConsumerBook;
import java.util.List;

/**
 * A factory that keeps the engine it built, which is the only way to see inside it afterwards, with
 * the comparison's view of the book: queue order, displayed quantity only.
 */
final class Engines implements MatchingEngineFactory {

  PooledEngine engine;

  @Override
  public MatchingEngine create(final EventPublisher events) {
    engine = new PooledEngine(events);
    return engine;
  }

  static List<ConsumerBook.Entry> visible(final List<Order> resting) {
    return resting.stream()
        .sorted(Order.BY_ARRIVAL)
        .map(
            order ->
                new ConsumerBook.Entry(order.id(), order.side(), order.price(), order.displayed()))
        .toList();
  }
}

package io.github.giovanicaprison.matching.flyweight;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import io.github.giovanicaprison.matching.conformance.ConsumerBook;
import io.github.giovanicaprison.matching.protocol.Side;
import java.util.Comparator;
import java.util.List;

/**
 * A factory that keeps the engine it built, which is the only way to see inside it afterwards, with
 * the comparison's view of the book: queue order, displayed quantity only.
 */
final class Engines implements MatchingEngineFactory {

  FlyweightEngine engine;

  @Override
  public MatchingEngine create(final EventPublisher events) {
    engine = new FlyweightEngine(events);
    return engine;
  }

  static List<ConsumerBook.Entry> visible(final FlyweightEngine engine) {
    final Slab slab = engine.slab();
    final Book book = engine.book();
    return book.restingSlots().stream()
        .sorted(Comparator.comparingLong(slab::arrival))
        .map(
            slot ->
                new ConsumerBook.Entry(
                    slab.id(slot),
                    Side.get((short) slab.side(slot)),
                    book.priceOfTick(slab.tick(slot)),
                    slab.displayed(slot)))
        .toList();
  }
}

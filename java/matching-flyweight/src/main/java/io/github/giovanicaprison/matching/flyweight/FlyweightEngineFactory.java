package io.github.giovanicaprison.matching.flyweight;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;

/** Builds rung three. */
public final class FlyweightEngineFactory implements MatchingEngineFactory {

  @Override
  public MatchingEngine create(final EventPublisher events) {
    return new FlyweightEngine(events);
  }
}

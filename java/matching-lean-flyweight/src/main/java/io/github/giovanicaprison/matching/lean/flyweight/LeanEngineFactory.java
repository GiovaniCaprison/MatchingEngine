package io.github.giovanicaprison.matching.lean.flyweight;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;

/** Builds rung three's lean twin. */
public final class LeanEngineFactory implements MatchingEngineFactory {

  @Override
  public MatchingEngine create(final EventPublisher events) {
    return new LeanEngine(events);
  }
}

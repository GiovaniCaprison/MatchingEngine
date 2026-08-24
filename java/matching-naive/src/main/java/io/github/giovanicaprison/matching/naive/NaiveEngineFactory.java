package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;

/** Builds rung zero. */
public final class NaiveEngineFactory implements MatchingEngineFactory {

  @Override
  public MatchingEngine create(final EventPublisher events) {
    return new NaiveEngine(events);
  }
}

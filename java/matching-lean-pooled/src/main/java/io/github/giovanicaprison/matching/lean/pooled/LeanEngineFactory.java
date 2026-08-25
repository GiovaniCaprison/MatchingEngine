package io.github.giovanicaprison.matching.lean.pooled;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;

/** Builds the limit-and-market arm at the indexed layout (P-16). */
public final class LeanEngineFactory implements MatchingEngineFactory {

  @Override
  public MatchingEngine create(final EventPublisher events) {
    return new LeanEngine(events);
  }
}

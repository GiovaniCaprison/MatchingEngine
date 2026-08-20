package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.api.EventSink;
import io.github.giovanicaprison.matching.api.Instrument;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;

/** Builds a {@link NaiveMatchingEngine}. */
public final class NaiveMatchingEngineFactory implements MatchingEngineFactory {

  @Override
  public MatchingEngine create(final Instrument instrument, final EventSink sink) {
    return new NaiveMatchingEngine(instrument, sink);
  }
}

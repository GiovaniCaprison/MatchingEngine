package io.github.giovanicaprison.matching.api;

/**
 * Builds an engine.
 *
 * <p>It exists so that the conformance suite and the benchmarks can hold every implementation to
 * the same treatment without naming any of them. That is a substitution that can be named, which is
 * the only reason to write an interface (P-15).
 */
public interface MatchingEngineFactory {

  /**
   * A fresh engine with an empty book.
   *
   * @param instrument the instrument this engine handles for its whole life
   * @param sink where its events go
   */
  MatchingEngine create(Instrument instrument, EventSink sink);
}

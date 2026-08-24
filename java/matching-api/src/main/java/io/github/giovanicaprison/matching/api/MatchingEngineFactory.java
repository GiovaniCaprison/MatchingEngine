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
   * A fresh engine with no instrument configured and an empty book.
   *
   * <p>The instrument arrives as a command (FR-1.1), so there is nothing to configure here. Holding
   * reference data in a Java type would also leave the C++ side without a counterpart, since it
   * reads the same definition off the wire.
   *
   * @param events where the engine's events go
   */
  MatchingEngine create(EventPublisher events);
}

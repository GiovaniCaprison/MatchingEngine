package io.github.giovanicaprison.matching.conformance;

/**
 * The events a fixture can expect. One per event in the protocol.
 *
 * <p>Disjoint from {@link Directive} on purpose. That is what lets a fixture put an event on the
 * line below the command that caused it without a marker to say which is which.
 */
public enum Verb {
  ACCEPTED,
  REJECTED,
  RESTED,
  EXECUTED,
  REDUCED,
  REMOVED,
  TRIGGERED,
  STATE,
  INDICATIVE
}

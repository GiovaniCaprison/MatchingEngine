package io.github.giovanicaprison.matching.conformance;

/**
 * The commands a fixture can send. One per command in the protocol.
 *
 * <p>Kept as a bare list of names because the document consistency gate reads this file as text and
 * holds {@code TESTING.md} to it. A constant carrying arguments would break that.
 */
public enum Directive {
  INSTRUMENT,
  SESSION,
  NEW,
  CANCEL,
  REPLACE,
  MASSCANCEL
}

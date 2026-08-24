package io.github.giovanicaprison.matching.api;

import org.agrona.MutableDirectBuffer;

/**
 * Where an engine writes the events it produces.
 *
 * <p>The engine claims space, encodes into it and says when the write is done. It never hands over
 * a buffer it filled elsewhere, because the consumer would then have to copy the bytes into
 * whatever it publishes from, and that is a copy per event no real deployment pays. Claiming and
 * encoding in place is what LMAX, Aeron and Chronicle do.
 *
 * <p>The point of the shape is where the consumer runs. A callback that receives an event has to do
 * its work on the engine's thread, inside the command being applied, so counting events or
 * checksumming a stream lands in the middle of the thing those checks exist to protect. Behind this
 * interface the consumer is on another core, reading a ring the engine only ever writes to.
 *
 * <p>Encoded bytes rather than typed callbacks, on purpose. Typed callbacks carrying primitives
 * would move the cost of encoding an event out of the implementation and into nowhere, which would
 * make an implementation that encodes cheaply indistinguishable from one that does not. Decode sits
 * inside the measurement, so encode does too.
 *
 * <p>The contract, all of it a precondition rather than a check (P-14):
 *
 * <ul>
 *   <li>one claim outstanding at a time, committed before the next
 *   <li>writes go to {@link #buffer()} between the offset returned and that offset plus the length
 *       claimed
 *   <li>{@link #buffer()} returns the same buffer for the life of the publisher, so an engine may
 *       hold the reference
 * </ul>
 *
 * <p>{@link #claim(int)} waits rather than failing. A publisher with no room is back pressure, and
 * an engine that could drop an event on it is an engine whose output stream cannot rebuild a book.
 * How long it waited is the publisher's business to count and a run's business to report.
 */
public interface EventPublisher {

  /**
   * Space for one event.
   *
   * @param length how many bytes the event will occupy
   * @return the offset in {@link #buffer()} to encode at
   */
  int claim(int length);

  /** The buffer claims are made in. The same one every time. */
  MutableDirectBuffer buffer();

  /** Publishes the outstanding claim. The event is a consumer's to read from here. */
  void commit();
}

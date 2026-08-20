package io.github.giovanicaprison.matching.api;

import org.agrona.DirectBuffer;

/**
 * Where an engine puts the events it produces.
 *
 * <p>Encoded bytes rather than typed callbacks, on purpose. Typed callbacks carrying primitives
 * would move the cost of encoding an event out of the implementation and into nowhere, which would
 * make an implementation that encodes cheaply indistinguishable from one that does not. Decode sits
 * inside the measurement, so encode does too.
 *
 * <p>Emitting rather than returning is what keeps an engine off the allocator (P-8). A consumer that
 * only counts executions materialises nothing at all.
 *
 * <p>Called synchronously on the writer thread, inside the command being applied. An implementation
 * that blocks, allocates without bound, or throws will stall or corrupt the engine: throwing part
 * way through a command leaves the book correct and the outbound record incomplete, and there is no
 * way back from that. A consumer with real work to do hands off to its own queue.
 *
 * <p>The slice is valid for the duration of the call and no longer. A consumer that needs to keep an
 * event copies it.
 */
public interface EventSink {

  /**
   * One encoded event.
   *
   * @param buffer the buffer holding the event
   * @param offset where the event starts
   * @param length how many bytes it occupies
   */
  void onEvent(DirectBuffer buffer, int offset, int length);
}

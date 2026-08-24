package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.api.EventPublisher;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;

/**
 * An engine's events, published into a ring the verifier reads from another core.
 *
 * <p>A claim that cannot be satisfied waits, because an engine that drops an event on back pressure
 * is an engine whose output cannot rebuild a book. Waiting is counted rather than hidden: a run
 * that spent time here was limited by its consumer, and its numbers describe the harness.
 *
 * <p>The spin has no yield in it. Yielding to the scheduler on the measured core is a larger cost
 * than the wait it avoids, and the consumer is on a core of its own with nothing to yield to.
 */
final class RingPublisher implements EventPublisher {

  private final OneToOneRingBuffer ring;

  private int claimed = -1;
  private long waits;
  private long waitedNanos;

  RingPublisher(final OneToOneRingBuffer ring) {
    this.ring = ring;
  }

  @Override
  public int claim(final int length) {
    int index = ring.tryClaim(Rings.EVENT, length);
    if (index < 0) {
      index = waitForRoom(length);
    }
    claimed = index;
    return index;
  }

  @Override
  public MutableDirectBuffer buffer() {
    return ring.buffer();
  }

  @Override
  public void commit() {
    ring.commit(claimed);
    claimed = -1;
  }

  /** How many times the ring had no room, and how long that cost in total. */
  long waits() {
    return waits;
  }

  long waitedNanos() {
    return waitedNanos;
  }

  private int waitForRoom(final int length) {
    final long from = System.nanoTime();
    waits++;
    int index;
    do {
      Thread.onSpinWait();
      index = ring.tryClaim(Rings.EVENT, length);
    } while (index < 0);
    waitedNanos += System.nanoTime() - from;
    return index;
  }
}

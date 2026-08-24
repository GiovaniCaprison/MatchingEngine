package io.github.giovanicaprison.matching.benchmarks;

import org.agrona.BitUtil;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/**
 * The queues between the driver, the engine and the verifier.
 *
 * <p>One producer and one consumer at each end, which is the shape a venue deploys and the reason
 * the engine's core does nothing but the engine. Agrona's implementation has an exact twin in the
 * Aeron C client, so the C++ side runs the same algorithm rather than a second interpretation of
 * it.
 *
 * <p>Off heap and cache line aligned. On heap the buffer is a byte array a collector may relocate
 * and a write to it is a card mark, and neither belongs in a measurement.
 */
final class Rings {

  /** An event on its way to the verifier. */
  static final int EVENT = 1;

  /** Nothing follows. The verifier stops rather than guessing how many events a run produces. */
  static final int END = 2;

  /** A command on its way to the engine. */
  static final int COMMAND = 3;

  private Rings() {}

  static OneToOneRingBuffer of(final int capacity) {
    if (!BitUtil.isPowerOfTwo(capacity)) {
      throw new IllegalArgumentException("a ring's capacity is a power of two: " + capacity);
    }
    return new OneToOneRingBuffer(
        new UnsafeBuffer(
            BufferUtil.allocateDirectAligned(
                capacity + RingBufferDescriptor.TRAILER_LENGTH, BitUtil.CACHE_LINE_LENGTH)));
  }
}

package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.api.EventSink;
import org.agrona.DirectBuffer;

/**
 * Counts events and reads one byte of each, so the JIT cannot delete the work that produced them.
 *
 * <p>A sink that decoded every event would put a consumer's cost inside the engine's number. A sink
 * that did nothing at all would let escape analysis remove encoding entirely. Touching one byte is
 * the cheapest thing that keeps the write real.
 */
public final class CountingEventSink implements EventSink {

  public long events;
  public long checksum;

  @Override
  public void onEvent(final DirectBuffer buffer, final int offset, final int length) {
    events++;
    checksum += buffer.getByte(offset);
  }
}

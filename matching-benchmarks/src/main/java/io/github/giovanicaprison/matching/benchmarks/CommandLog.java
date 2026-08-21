package io.github.giovanicaprison.matching.benchmarks;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * A run of commands, pre-encoded into one buffer with an index of where each starts.
 *
 * <p>Pre-encoded because the alternative is decoding text or generating from a seed inside the
 * measured loop, which would benchmark a tokeniser or a random number generator. The engine's own
 * decode stays inside the measurement, because that is part of an implementation and part of its
 * cost. Framing does not.
 *
 * <p>This is also the artefact a C++ process would be handed, since it is a sequence of framed
 * messages and nothing else.
 */
public final class CommandLog {

  private final UnsafeBuffer buffer;
  private final int[] offsets;
  private final int[] lengths;
  private final int count;

  CommandLog(final UnsafeBuffer buffer, final int[] offsets, final int[] lengths, final int count) {
    this.buffer = buffer;
    this.offsets = offsets;
    this.lengths = lengths;
    this.count = count;
  }

  public DirectBuffer buffer() {
    return buffer;
  }

  public int count() {
    return count;
  }

  public int offset(final int index) {
    return offsets[index];
  }

  public int length(final int index) {
    return lengths[index];
  }
}

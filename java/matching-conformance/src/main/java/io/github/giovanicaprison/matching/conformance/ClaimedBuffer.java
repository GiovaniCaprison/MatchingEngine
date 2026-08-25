package io.github.giovanicaprison.matching.conformance;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The claim and commit bookkeeping an in-process publisher needs, shared by both runners.
 *
 * <p>A ring in miniature: one buffer, a cursor that wraps, and the last claim held so a commit
 * knows what to read. What a commit does with the event differs between the runners, so that stays
 * with them, and the wrap arithmetic lives here once.
 */
final class ClaimedBuffer {

  /** Room for the largest burst one command can produce, with no reason to be tight about it. */
  private static final int CAPACITY = 1 << 20;

  private final MutableDirectBuffer events = new UnsafeBuffer(new byte[CAPACITY]);

  private int cursor;
  private int claimed;
  private int claimedLength;

  int claim(final int length) {
    if (cursor + length > CAPACITY) {
      cursor = 0;
    }
    claimed = cursor;
    claimedLength = length;
    cursor += length;
    return claimed;
  }

  MutableDirectBuffer buffer() {
    return events;
  }

  int claimed() {
    return claimed;
  }

  int claimedLength() {
    return claimedLength;
  }
}

package io.github.giovanicaprison.matching.flow;

import io.github.giovanicaprison.matching.protocol.Side;

/**
 * The orders the generator believes are still there.
 *
 * <p>Believes, because it never sees an execution. An entry survives here until the generator
 * itself cancels it or mass cancels its participant, so a cancel of an order that has already
 * traded is possible and intended: that is a real command a real venue receives.
 *
 * <p>Parallel arrays with removal by swapping in the last entry. Order within the structure is not
 * meaningful, and a uniform draw over a dense array is the cheapest way to pick a target that is
 * still live.
 */
final class Resting {

  private final int[] ordinals;
  private final int[] participants;
  private final long[] prices;
  private final Side[] sides;

  private int count;

  Resting(final int capacity) {
    ordinals = new int[capacity];
    participants = new int[capacity];
    prices = new long[capacity];
    sides = new Side[capacity];
  }

  boolean any() {
    return count > 0;
  }

  void add(final int ordinal, final int participant, final Side side, final long price) {
    if (count == ordinals.length) {
      return;
    }
    ordinals[count] = ordinal;
    participants[count] = participant;
    sides[count] = side;
    prices[count] = price;
    count++;
  }

  int pick(final Sequence sequence) {
    return sequence.nextInt(count);
  }

  int ordinalAt(final int index) {
    return ordinals[index];
  }

  int participantAt(final int index) {
    return participants[index];
  }

  long priceAt(final int index) {
    return prices[index];
  }

  Side sideAt(final int index) {
    return sides[index];
  }

  void priceAt(final int index, final long price) {
    prices[index] = price;
  }

  void removeAt(final int index) {
    count--;
    ordinals[index] = ordinals[count];
    participants[index] = participants[count];
    sides[index] = sides[count];
    prices[index] = prices[count];
  }

  /** Everything for one participant is gone, so a later cancel of one would name a dead order. */
  void forget(final int participant) {
    for (int index = count - 1; index >= 0; index--) {
      if (participants[index] == participant) {
        removeAt(index);
      }
    }
  }
}

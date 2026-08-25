package io.github.giovanicaprison.matching.flyweight;

import java.util.Arrays;

/**
 * A reusable gathering space for the commands that touch many orders at once, holding slots rather
 * than objects.
 *
 * <p>A mass cancel and an uncrossing collect orders, sort them by arrival and walk the result. The
 * space is kept and the sort is insertion sort, because a library sort allocates its working space
 * per call and this ladder of rungs stopped doing that one rung ago (NFR-4.3). These are the
 * venue's large commands already (P-9) and their cost is theirs to carry.
 */
final class IntScratch {

  private int[] slots = new int[1024];
  private int count;

  void clear() {
    count = 0;
  }

  void add(final int slot) {
    if (count == slots.length) {
      slots = Arrays.copyOf(slots, count * 2);
    }
    slots[count++] = slot;
  }

  int size() {
    return count;
  }

  int get(final int index) {
    return slots[index];
  }

  /** Earliest first, stably, in place. Arrivals are unique, so any correct sort agrees. */
  void sortByArrival(final Slab slab) {
    for (int i = 1; i < count; i++) {
      final int slot = slots[i];
      final long arrival = slab.arrival(slot);
      int at = i - 1;
      while (at >= 0 && slab.arrival(slots[at]) > arrival) {
        slots[at + 1] = slots[at];
        at--;
      }
      slots[at + 1] = slot;
    }
  }
}

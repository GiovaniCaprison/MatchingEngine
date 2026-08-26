package io.github.giovanicaprison.matching.lean.flyweight;

import java.util.Arrays;

/**
 * A reusable gathering space for the one command here that touches many orders at once, holding
 * slots rather than objects. The space is kept and the sort is insertion sort, because a library
 * sort allocates its working space per call and the steady state does not (NFR-4.3, P-9).
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

package io.github.giovanicaprison.matching.pooled;

import java.util.Arrays;

/**
 * A reusable gathering space for the commands that touch many orders at once.
 *
 * <p>A mass cancel and an uncrossing collect orders, sort them by arrival and walk the result. On
 * the rungs below that was a fresh list and a library sort per command; here the space is kept and
 * the sort is insertion sort, because a library sort allocates its working space per call and this
 * rung's claim is that the steady state does not (NFR-4.3). Insertion sort is quadratic, but these
 * are the venue's large commands already (P-9) and their cost is theirs to carry.
 */
final class Scratch {

  private Order[] orders = new Order[64];
  private int count;

  void clear() {
    count = 0;
  }

  void add(final Order order) {
    if (count == orders.length) {
      orders = Arrays.copyOf(orders, count * 2);
    }
    orders[count++] = order;
  }

  int size() {
    return count;
  }

  Order get(final int index) {
    return orders[index];
  }

  /** Earliest first, stably, in place. Arrivals are unique, so any correct sort agrees. */
  void sortByArrival() {
    for (int i = 1; i < count; i++) {
      final Order order = orders[i];
      int at = i - 1;
      while (at >= 0 && orders[at].arrival() > order.arrival()) {
        orders[at + 1] = orders[at];
        at--;
      }
      orders[at + 1] = order;
    }
  }
}

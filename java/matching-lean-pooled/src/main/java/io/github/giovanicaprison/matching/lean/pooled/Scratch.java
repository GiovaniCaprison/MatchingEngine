package io.github.giovanicaprison.matching.lean.pooled;

import java.util.Arrays;

/**
 * A reusable gathering space for the one command that touches many orders at once.
 *
 * <p>A mass cancel collects a participant's orders, sorts them by arrival and walks the result. On
 * the rungs below that was a fresh list and a library sort per command; here the space is kept and
 * the sort is insertion sort, because a library sort allocates its working space per call and this
 * rung's claim is that the steady state does not (NFR-4.3).
 */
final class Scratch {

  private Order[] orders = new Order[1024];
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

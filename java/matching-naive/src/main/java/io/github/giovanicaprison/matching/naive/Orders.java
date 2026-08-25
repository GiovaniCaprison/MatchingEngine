package io.github.giovanicaprison.matching.naive;

import java.util.ArrayList;
import java.util.List;

/**
 * The scans the book and the trigger book share.
 *
 * <p>Both structures are a list walked end to end, and finding an order by its owner is the same
 * walk in both. Sharing the loop changes nothing about its cost, which stays this rung's to pay;
 * what it removes is two copies of one loop drifting apart.
 */
final class Orders {

  private Orders() {}

  /** The order one participant gave one client order id to, or null when nothing matches. */
  static Order named(final List<Order> orders, final int participantId, final long clientOrderId) {
    for (final Order order : orders) {
      if (order.participantId() == participantId && order.clientOrderId() == clientOrderId) {
        return order;
      }
    }
    return null;
  }

  /** Everything one participant has, in arrival order, which is how a mass cancel reports. */
  static List<Order> of(final List<Order> orders, final int participantId) {
    final List<Order> found = new ArrayList<>();
    for (final Order order : orders) {
      if (order.participantId() == participantId) {
        found.add(order);
      }
    }
    found.sort(Order.BY_ARRIVAL);
    return found;
  }
}

package io.github.giovanicaprison.matching.lean.naive;

import io.github.giovanicaprison.matching.protocol.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * One list, scanned, exactly as the full rung's book is. The two engines share their structure on
 * purpose: the comparison between them has to isolate the feature set, so a structural difference
 * here would put a second variable inside the one question this engine exists to answer.
 */
final class Book {

  private final List<Order> orders = new ArrayList<>();

  void add(final Order order) {
    orders.add(order);
  }

  void remove(final Order order) {
    orders.remove(order);
  }

  List<Order> orders() {
    return orders;
  }

  Order named(final int participantId, final long clientOrderId) {
    for (final Order order : orders) {
      if (order.participantId() == participantId && order.clientOrderId() == clientOrderId) {
        return order;
      }
    }
    return null;
  }

  /** The order a taker reaches next: best price first, then earliest arrival (FR-3.1, FR-3.3). */
  Order nextToTake(final Side takerSide, final long limit) {
    final Side restingSide = opposite(takerSide);
    Order best = null;
    for (final Order order : orders) {
      if (order.side() != restingSide || !crosses(takerSide, limit, order.price())) {
        continue;
      }
      if (best == null
          || better(restingSide, order.price(), best.price())
          || (order.price() == best.price() && order.arrival() < best.arrival())) {
        best = order;
      }
    }
    return best;
  }

  /** Every order for one participant, in arrival order, which is how a mass cancel reports. */
  List<Order> of(final int participantId) {
    final List<Order> found = new ArrayList<>();
    for (final Order order : orders) {
      if (order.participantId() == participantId) {
        found.add(order);
      }
    }
    found.sort(Order.BY_ARRIVAL);
    return found;
  }

  static Side opposite(final Side side) {
    return side == Side.BUY ? Side.SELL : Side.BUY;
  }

  static boolean crosses(final Side takerSide, final long limit, final long price) {
    if (limit == 0) {
      return true;
    }
    return takerSide == Side.BUY ? price <= limit : price >= limit;
  }

  static boolean better(final Side side, final long price, final long than) {
    return side == Side.BUY ? price > than : price < than;
  }
}

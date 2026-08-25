package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.protocol.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * One list, scanned.
 *
 * <p>Every question this book answers is a walk over every order it holds. Finding the best price
 * is a scan, finding an order by id is a scan, and taking the orders at one price is another. That
 * is the point of rung zero: the cost of not indexing anything is what the rungs above it are
 * measured against.
 *
 * <p>The waste that follows from the representation stays. A second scan where a cached best price
 * would do is what this rung is; a second scan that no representation makes necessary is a defect.
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

  /**
   * The order one participant gave one client order id to.
   *
   * <p>A scan, because an index on the pair is the next rung's idea.
   */
  Order named(final int participantId, final long clientOrderId) {
    return Orders.named(orders, participantId, clientOrderId);
  }

  /**
   * The order a taker reaches next: best price first, then earliest arrival (FR-3.1, FR-3.3).
   *
   * <p>A limit of zero means the taker has no price of its own, which is what a market order is.
   */
  Order nextToTake(final Side takerSide, final long limit) {
    final Side restingSide = opposite(takerSide);
    Order best = null;
    for (final Order order : orders) {
      if (order.side() != restingSide || !crosses(takerSide, limit, order.price())) {
        continue;
      }
      if (best == null
          || better(restingSide, order.price(), best.price())
          || order.price() == best.price() && order.arrival() < best.arrival()) {
        best = order;
      }
    }
    return best;
  }

  /** Everything resting at one price on one side, in arrival order, for a pro-rata allocation. */
  List<Order> atPrice(final Side side, final long price) {
    final List<Order> found = new ArrayList<>();
    for (final Order order : orders) {
      if (order.side() == side && order.price() == price) {
        found.add(order);
      }
    }
    found.sort(Order.BY_ARRIVAL);
    return found;
  }

  /**
   * Every order for one participant, in arrival order, which is how a mass cancel reports (FR-4.7).
   */
  List<Order> of(final int participantId) {
    return Orders.of(orders, participantId);
  }

  /** How much of a taker's order the book could fill, for the orders that have to know first. */
  long fillable(final Side takerSide, final long limit, final long smpId) {
    long total = 0;
    for (final Order order : orders) {
      if (order.side() != opposite(takerSide) || !crosses(takerSide, limit, order.price())) {
        continue;
      }
      if (smpId != 0 && order.smpId() == smpId) {
        continue;
      }
      total += order.remaining();
    }
    return total;
  }

  static Side opposite(final Side side) {
    return side == Side.BUY ? Side.SELL : Side.BUY;
  }

  /** Whether a taker at {@code limit} can reach a resting order at {@code price}. */
  static boolean crosses(final Side takerSide, final long limit, final long price) {
    if (limit == 0) {
      return true;
    }
    return takerSide == Side.BUY ? price <= limit : price >= limit;
  }

  /** Which of two prices is in front on one side: higher for a bid, lower for an offer. */
  static boolean better(final Side side, final long price, final long than) {
    return side == Side.BUY ? price > than : price < than;
  }
}

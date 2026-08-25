package io.github.giovanicaprison.matching.lean.indexed;

import io.github.giovanicaprison.matching.protocol.Side;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The indexed rung's book with nothing on it the lean remit does not need: sorted levels, intrusive
 * queues and a name index, exactly as the full rung holds them, so the comparison between the two
 * isolates the feature set at this layout and not a structural difference. What is missing is the
 * feature machinery: no pro-rata level walk, no fillable pre-check, no re-queueing, because nothing
 * here replenishes.
 */
final class Book {

  /** One price: its queue as an intrusive chain, and the running total of what it shows. */
  static final class Level {

    private final long price;
    private Order head;
    private Order tail;
    private long displayed;

    private Level(final long price) {
      this.price = price;
    }

    private void append(final Order order) {
      order.previous = tail;
      order.next = null;
      if (tail == null) {
        head = order;
      } else {
        tail.next = order;
      }
      tail = order;
    }

    private void unlink(final Order order) {
      if (order.previous == null) {
        head = order.next;
      } else {
        order.previous.next = order.next;
      }
      if (order.next == null) {
        tail = order.previous;
      } else {
        order.next.previous = order.previous;
      }
      order.previous = null;
      order.next = null;
    }
  }

  private record Name(int participantId, long clientOrderId) {

    static Name of(final Order order) {
      return new Name(order.participantId(), order.clientOrderId());
    }
  }

  private final TreeMap<Long, Level> bids = new TreeMap<>(Comparator.reverseOrder());
  private final TreeMap<Long, Level> asks = new TreeMap<>();
  private final Map<Name, Order> byName = new HashMap<>();

  void add(final Order order) {
    final Level level =
        side(order.side()).computeIfAbsent(order.price(), price -> new Level(price));
    level.append(order);
    level.displayed += order.remaining();
    byName.put(Name.of(order), order);
  }

  void remove(final Order order) {
    final Level level = side(order.side()).get(order.price());
    level.unlink(order);
    level.displayed -= order.remaining();
    if (level.head == null) {
      side(order.side()).remove(order.price());
    }
    byName.remove(Name.of(order));
  }

  /** The order's remaining quantity changed in place, so the level's total follows. */
  void displayedChanged(final Order order, final long before) {
    side(order.side()).get(order.price()).displayed += order.remaining() - before;
  }

  Order named(final int participantId, final long clientOrderId) {
    return byName.get(new Name(participantId, clientOrderId));
  }

  /** The order a taker reaches next: the front of the best crossing level (FR-3.1, FR-3.3). */
  Order nextToTake(final Side takerSide, final long limit) {
    final Map.Entry<Long, Level> best = side(opposite(takerSide)).firstEntry();
    if (best == null || !crosses(takerSide, limit, best.getKey())) {
      return null;
    }
    return best.getValue().head;
  }

  /** Every order for one participant, in arrival order. Still a walk, on purpose. */
  List<Order> of(final int participantId) {
    final List<Order> found = new ArrayList<>();
    for (final Order order : byName.values()) {
      if (order.participantId() == participantId) {
        found.add(order);
      }
    }
    found.sort(Order.BY_ARRIVAL);
    return found;
  }

  private TreeMap<Long, Level> side(final Side side) {
    return side == Side.BUY ? bids : asks;
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
}

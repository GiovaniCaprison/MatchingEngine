package io.github.giovanicaprison.matching.indexed;

import io.github.giovanicaprison.matching.protocol.Side;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sorted price levels and an index by the id a cancel carries. This is the rung: the questions the
 * naive book answered with a walk over everything are answered here by a lookup, and the price of
 * that is bookkeeping that can drift, which is exactly what the new invariants police (NFR-3.1,
 * NFR-3.2).
 *
 * <p>Each side is a map from price to level, best first, and a level is its queue in arrival order
 * with a running total of what it displays. The queue's order is maintained rather than derived: a
 * fresh order appends, a replenished tranche re-queues at the back, and a replace that keeps queue
 * position touches neither, which reproduces the naive book's arrival tie-break to the byte.
 *
 * <p>What this rung still does not index is deliberate. A participant's orders are found by walking
 * the levels, because a mass cancel is a large command wherever it lands (P-9), and the trigger
 * book stays a scanned list, so the step from rung zero isolates the book's structure and changes
 * nothing else.
 */
final class Book {

  /**
   * One price: its queue as an intrusive chain in arrival order, and the total the queue must
   * always sum to.
   *
   * <p>Intrusive because the orders carry their own links, so joining, leaving and re-queueing are
   * each a handful of pointer writes with nothing searched and nothing allocated. That is the
   * production shape of an indexed book, and it is more bookkeeping that can drift, which is what
   * the invariants walk both directions to catch (NFR-3.1, NFR-3.2).
   */
  static final class Level {

    private final long price;
    private Order head;
    private Order tail;
    private long displayed;

    private Level(final long price) {
      this.price = price;
    }

    long price() {
      return price;
    }

    /** The cached aggregate (NFR-3.1's subject), never recomputed on the hot path. */
    long displayed() {
      return displayed;
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

    private boolean isEmpty() {
      return head == null;
    }

    /** The queue front to back, materialised for the walks that want a list and for the tests. */
    List<Order> queue() {
      final List<Order> orders = new ArrayList<>();
      for (Order order = head; order != null; order = order.next) {
        orders.add(order);
      }
      return orders;
    }

    /** The queue back to front, so a test can prove the two directions tell one story. */
    List<Order> queueReversed() {
      final List<Order> orders = new ArrayList<>();
      for (Order order = tail; order != null; order = order.previous) {
        orders.add(order);
      }
      return orders;
    }
  }

  /** How a command names an order: the pair is unique per session, so it is the key (FR-4.1). */
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
    level.displayed += order.displayed();
    byName.put(Name.of(order), order);
  }

  void remove(final Order order) {
    final Level level = side(order.side()).get(order.price());
    level.unlink(order);
    level.displayed -= order.displayed();
    if (level.isEmpty()) {
      // (NFR-3.2) An empty level does not survive: a book that kept them would answer best-price
      // questions from prices nobody is at.
      side(order.side()).remove(order.price());
    }
    byName.remove(Name.of(order));
  }

  /** The order's displayed quantity changed in place, so the level's total follows (NFR-3.1). */
  void displayedChanged(final Order order, final long before) {
    side(order.side()).get(order.price()).displayed += order.displayed() - before;
  }

  /** A replenished tranche joins the back of the queue at its price (FR-5.4). */
  void requeued(final Order order, final long displayedBefore) {
    final Level level = side(order.side()).get(order.price());
    level.unlink(order);
    level.append(order);
    level.displayed += order.displayed() - displayedBefore;
  }

  Order named(final int participantId, final long clientOrderId) {
    return byName.get(new Name(participantId, clientOrderId));
  }

  /**
   * The order a taker reaches next: the front of the best crossing level (FR-3.1, FR-3.3), found by
   * lookup rather than by the walk the naive book pays.
   */
  Order nextToTake(final Side takerSide, final long limit) {
    final Map.Entry<Long, Level> best = side(opposite(takerSide)).firstEntry();
    if (best == null || !crosses(takerSide, limit, best.getKey())) {
      return null;
    }
    return best.getValue().head;
  }

  /** Everything resting at one price on one side, in arrival order, for a pro-rata allocation. */
  List<Order> atPrice(final Side side, final long price) {
    final Level level = side(side).get(price);
    return level == null ? List.of() : level.queue();
  }

  /** Every order for one participant, in arrival order (FR-4.7). Still a walk, on purpose. */
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

  /** How much a taker could fill, summed over crossing levels only rather than the whole book. */
  long fillable(final Side takerSide, final long limit, final long smpId) {
    long total = 0;
    for (final Level level : side(opposite(takerSide)).values()) {
      if (!crosses(takerSide, limit, level.price)) {
        return total;
      }
      for (Order order = level.head; order != null; order = order.next) {
        if (smpId == 0 || order.smpId() != smpId) {
          total += order.remaining();
        }
      }
    }
    return total;
  }

  /** Every resting order, for the auction's walks and for the tests that hold the book still. */
  List<Order> orders() {
    final List<Order> all = new ArrayList<>(byName.values());
    return all;
  }

  /** The levels of one side, best first, for the invariants that check the bookkeeping. */
  Collection<Level> levels(final Side side) {
    return side(side).values();
  }

  private TreeMap<Long, Level> side(final Side side) {
    return side == Side.BUY ? bids : asks;
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
}

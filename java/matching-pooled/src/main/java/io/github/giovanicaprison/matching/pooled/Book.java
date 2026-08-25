package io.github.giovanicaprison.matching.pooled;

import io.github.giovanicaprison.matching.protocol.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * The indexed book with the allocation taken out. Same shape as the rung below: sorted price
 * levels, intrusive queues in arrival order, an index by the id a cancel carries, and a cached
 * total per level. What changed is who provides the memory: levels are nodes of a hand-built tree
 * and go back to its free list when they empty, and the name index is open addressing over
 * primitive keys, so the steady state neither boxes a price nor allocates a node (NFR-4.3).
 *
 * <p>Both sides share one tree shape by folding the sort key: a bid level's key is its negated
 * price, so ascending key order is best-first on either side and every walk is the same walk.
 */
final class Book {

  /**
   * One price: its queue as an intrusive chain in arrival order, the total the queue must always
   * sum to (NFR-3.1), and the level's own place in its side's tree.
   *
   * <p>The level is the tree node. A node wrapping a level would be an allocation per price and an
   * indirection per comparison, and the point of this rung is that neither survives warm-up.
   */
  static final class Level {

    private long key;
    private Level left;
    private Level right;
    private int height;

    private long price;
    private Order head;
    private Order tail;
    private long displayed;

    long price() {
      return price;
    }

    /** The cached aggregate (NFR-3.1's subject), never recomputed on the hot path. */
    long displayed() {
      return displayed;
    }

    /** The front of the queue, for the walks that follow the orders' own links. */
    Order head() {
      return head;
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

    /** The queue front to back, materialised for the tests. */
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

  /**
   * An in-order walk over one side's levels, best price first, pausing between calls.
   *
   * <p>The walk carries its own stack because the nodes carry no parent link, and it is a reusable
   * object because a fresh iterator per question is exactly the allocation this rung removes. The
   * stack never grows: an AVL tree deeper than the array holds more levels than there are prices.
   */
  static final class Walk {

    private final Level[] stack = new Level[64];
    private int depth;

    private void start(final Level root) {
      depth = 0;
      descend(root);
    }

    Level next() {
      if (depth == 0) {
        return null;
      }
      final Level level = stack[--depth];
      descend(level.right);
      return level;
    }

    private void descend(Level node) {
      while (node != null) {
        stack[depth++] = node;
        node = node.left;
      }
    }
  }

  private Level bids;
  private Level asks;
  private Level freeLevels;
  private Level found;

  private final Walk fillableWalk = new Walk();

  private int[] nameParticipants;
  private long[] nameClients;
  private Order[] nameOrders;
  private int nameMask;
  private int names;

  Book() {
    for (int i = 0; i < 4096; i++) {
      final Level level = new Level();
      level.left = freeLevels;
      freeLevels = level;
    }
    allocateNames(1 << 16);
  }

  void add(final Order order) {
    final Level level = levelFor(order.side(), order.price());
    level.append(order);
    level.displayed += order.displayed();
    namePut(order);
  }

  void remove(final Order order) {
    final long key = keyOf(order.side(), order.price());
    final Level level = level(order.side(), key);
    level.unlink(order);
    level.displayed -= order.displayed();
    if (level.isEmpty()) {
      // (NFR-3.2) An empty level does not survive: a book that kept them would answer best-price
      // questions from prices nobody is at.
      deleteLevel(order.side(), key);
    }
    nameRemove(order.participantId(), order.clientOrderId());
  }

  /** The order's displayed quantity changed in place, so the level's total follows (NFR-3.1). */
  void displayedChanged(final Order order, final long before) {
    level(order.side(), keyOf(order.side(), order.price())).displayed += order.displayed() - before;
  }

  /** A replenished tranche joins the back of the queue at its price (FR-5.4). */
  void requeued(final Order order, final long displayedBefore) {
    final Level level = level(order.side(), keyOf(order.side(), order.price()));
    level.unlink(order);
    level.append(order);
    level.displayed += order.displayed() - displayedBefore;
  }

  Order named(final int participantId, final long clientOrderId) {
    int at = slotOf(participantId, clientOrderId);
    while (nameOrders[at] != null) {
      if (nameParticipants[at] == participantId && nameClients[at] == clientOrderId) {
        return nameOrders[at];
      }
      at = (at + 1) & nameMask;
    }
    return null;
  }

  /**
   * The order a taker reaches next: the front of the best crossing level (FR-3.1, FR-3.3), found by
   * lookup rather than by the walk the naive book pays.
   */
  Order nextToTake(final Side takerSide, final long limit) {
    final Level best = best(opposite(takerSide));
    if (best == null || !crosses(takerSide, limit, best.price)) {
      return null;
    }
    return best.head;
  }

  /** The level at one price on one side, or null, for a pro-rata allocation's snapshot. */
  Level levelAt(final Side side, final long price) {
    return level(side, keyOf(side, price));
  }

  /** Every order for one participant appended to the caller's space (FR-4.7). Still a walk. */
  void of(final int participantId, final Scratch into) {
    for (int at = 0; at <= nameMask; at++) {
      final Order order = nameOrders[at];
      if (order != null && order.participantId() == participantId) {
        into.add(order);
      }
    }
  }

  /** How much a taker could fill, summed over crossing levels only rather than the whole book. */
  long fillable(final Side takerSide, final long limit, final long smpId) {
    long total = 0;
    walk(opposite(takerSide), fillableWalk);
    for (Level level = fillableWalk.next(); level != null; level = fillableWalk.next()) {
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

  /** Points a caller's walk at one side's levels, best first. */
  void walk(final Side side, final Walk walk) {
    walk.start(side == Side.BUY ? bids : asks);
  }

  /** Every resting order, allocated freshly, for the tests that hold the book still. */
  List<Order> orders() {
    final List<Order> all = new ArrayList<>();
    for (int at = 0; at <= nameMask; at++) {
      if (nameOrders[at] != null) {
        all.add(nameOrders[at]);
      }
    }
    return all;
  }

  /** The levels of one side, best first, allocated freshly, for the invariants. */
  List<Level> levels(final Side side) {
    final List<Level> all = new ArrayList<>();
    final Walk walk = new Walk();
    walk(side, walk);
    for (Level level = walk.next(); level != null; level = walk.next()) {
      all.add(level);
    }
    return all;
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

  // The level tree -------------------------------------------------------------------------------

  private static long keyOf(final Side side, final long price) {
    return side == Side.BUY ? -price : price;
  }

  private Level best(final Side side) {
    Level node = side == Side.BUY ? bids : asks;
    if (node == null) {
      return null;
    }
    while (node.left != null) {
      node = node.left;
    }
    return node;
  }

  private Level level(final Side side, final long key) {
    Level node = side == Side.BUY ? bids : asks;
    while (node != null && node.key != key) {
      node = key < node.key ? node.left : node.right;
    }
    return node;
  }

  private Level levelFor(final Side side, final long price) {
    final long key = keyOf(side, price);
    if (side == Side.BUY) {
      bids = insert(bids, key, price);
    } else {
      asks = insert(asks, key, price);
    }
    return found;
  }

  private void deleteLevel(final Side side, final long key) {
    if (side == Side.BUY) {
      bids = delete(bids, key);
    } else {
      asks = delete(asks, key);
    }
  }

  private Level insert(final Level node, final long key, final long price) {
    if (node == null) {
      found = acquireLevel(key, price);
      return found;
    }
    if (key < node.key) {
      node.left = insert(node.left, key, price);
    } else if (key > node.key) {
      node.right = insert(node.right, key, price);
    } else {
      found = node;
      return node;
    }
    return balanced(node);
  }

  private Level delete(final Level node, final long key) {
    if (key < node.key) {
      node.left = delete(node.left, key);
    } else if (key > node.key) {
      node.right = delete(node.right, key);
    } else {
      if (node.left == null || node.right == null) {
        final Level child = node.left != null ? node.left : node.right;
        releaseLevel(node);
        return child;
      }
      // Two children: the successor's identity moves into this node and its own node is deleted
      // where it stood. Nothing outside the tree holds a level, so the move is invisible.
      Level successor = node.right;
      while (successor.left != null) {
        successor = successor.left;
      }
      node.key = successor.key;
      node.price = successor.price;
      node.head = successor.head;
      node.tail = successor.tail;
      node.displayed = successor.displayed;
      node.right = deleteLeftmost(node.right);
    }
    return balanced(node);
  }

  private Level deleteLeftmost(final Level node) {
    if (node.left == null) {
      final Level child = node.right;
      releaseLevel(node);
      return child;
    }
    node.left = deleteLeftmost(node.left);
    return balanced(node);
  }

  private static int heightOf(final Level node) {
    return node == null ? 0 : node.height;
  }

  private static void measure(final Level node) {
    node.height = 1 + Math.max(heightOf(node.left), heightOf(node.right));
  }

  private static Level balanced(final Level node) {
    measure(node);
    final int lean = heightOf(node.left) - heightOf(node.right);
    if (lean > 1) {
      if (heightOf(node.left.left) < heightOf(node.left.right)) {
        node.left = rotateLeft(node.left);
      }
      return rotateRight(node);
    }
    if (lean < -1) {
      if (heightOf(node.right.right) < heightOf(node.right.left)) {
        node.right = rotateRight(node.right);
      }
      return rotateLeft(node);
    }
    return node;
  }

  private static Level rotateLeft(final Level node) {
    final Level pivot = node.right;
    node.right = pivot.left;
    pivot.left = node;
    measure(node);
    measure(pivot);
    return pivot;
  }

  private static Level rotateRight(final Level node) {
    final Level pivot = node.left;
    node.left = pivot.right;
    pivot.right = node;
    measure(node);
    measure(pivot);
    return pivot;
  }

  private Level acquireLevel(final long key, final long price) {
    Level level = freeLevels;
    if (level == null) {
      level = new Level();
    } else {
      freeLevels = level.left;
    }
    level.key = key;
    level.price = price;
    level.left = null;
    level.right = null;
    level.height = 1;
    level.head = null;
    level.tail = null;
    level.displayed = 0;
    return level;
  }

  private void releaseLevel(final Level level) {
    level.left = freeLevels;
    level.right = null;
    level.head = null;
    level.tail = null;
    freeLevels = level;
  }

  // The name index -------------------------------------------------------------------------------

  private void allocateNames(final int capacity) {
    nameParticipants = new int[capacity];
    nameClients = new long[capacity];
    nameOrders = new Order[capacity];
    nameMask = capacity - 1;
  }

  private int slotOf(final int participantId, final long clientOrderId) {
    long hash = clientOrderId * 0x9E3779B97F4A7C15L ^ participantId * 0xC2B2AE3D27D4EB4FL;
    hash ^= hash >>> 32;
    return (int) hash & nameMask;
  }

  private void namePut(final Order order) {
    if ((names + 1) * 2 > nameMask + 1) {
      grow();
    }
    int at = slotOf(order.participantId(), order.clientOrderId());
    while (nameOrders[at] != null) {
      at = (at + 1) & nameMask;
    }
    nameParticipants[at] = order.participantId();
    nameClients[at] = order.clientOrderId();
    nameOrders[at] = order;
    names++;
  }

  private void nameRemove(final int participantId, final long clientOrderId) {
    int at = slotOf(participantId, clientOrderId);
    while (nameParticipants[at] != participantId || nameClients[at] != clientOrderId) {
      at = (at + 1) & nameMask;
    }
    names--;
    // Backward shift rather than a tombstone: the table's occupancy is its contents, so the load
    // never quietly climbs and the steady state never rehashes (NFR-4.3).
    int hole = at;
    int probe = (hole + 1) & nameMask;
    while (nameOrders[probe] != null) {
      final int home = slotOf(nameParticipants[probe], nameClients[probe]);
      final boolean reachable = ((probe - home) & nameMask) >= ((probe - hole) & nameMask);
      if (reachable) {
        nameParticipants[hole] = nameParticipants[probe];
        nameClients[hole] = nameClients[probe];
        nameOrders[hole] = nameOrders[probe];
        hole = probe;
      }
      probe = (probe + 1) & nameMask;
    }
    nameOrders[hole] = null;
  }

  private void grow() {
    final int[] participants = nameParticipants;
    final long[] clients = nameClients;
    final Order[] orders = nameOrders;
    allocateNames((nameMask + 1) * 2);
    names = 0;
    for (int at = 0; at < orders.length; at++) {
      if (orders[at] != null) {
        names++;
        int to = slotOf(participants[at], clients[at]);
        while (nameOrders[to] != null) {
          to = (to + 1) & nameMask;
        }
        nameParticipants[to] = participants[at];
        nameClients[to] = clients[at];
        nameOrders[to] = orders[at];
      }
    }
  }
}

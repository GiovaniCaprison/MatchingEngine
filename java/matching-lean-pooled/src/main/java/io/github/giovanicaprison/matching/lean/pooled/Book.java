package io.github.giovanicaprison.matching.lean.pooled;

import io.github.giovanicaprison.matching.protocol.Side;

/**
 * The pooled rung's book with nothing on it the lean remit does not need: the same hand-built level
 * tree, intrusive queues and open-addressing name index the full rung holds, so the comparison
 * between the two isolates the feature set at this layout and not a structural difference. What is
 * missing is the feature machinery: no pro-rata level walk, no fillable pre-check, no re-queueing,
 * because nothing here replenishes. And like the rung it shadows, the steady state neither boxes a
 * key nor allocates a node (NFR-4.3).
 */
final class Book {

  /** One price: its queue, the total it holds, and its own place in its side's tree. */
  static final class Level {

    private long key;
    private Level left;
    private Level right;
    private int height;

    private long price;
    private Order head;
    private Order tail;
    private long displayed;

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

  private Level bids;
  private Level asks;
  private Level freeLevels;
  private Level found;

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
    level.displayed += order.remaining();
    namePut(order);
  }

  void remove(final Order order) {
    final long key = keyOf(order.side(), order.price());
    final Level level = level(order.side(), key);
    level.unlink(order);
    level.displayed -= order.remaining();
    if (level.head == null) {
      deleteLevel(order.side(), key);
    }
    nameRemove(order.participantId(), order.clientOrderId());
  }

  /** The order's remaining quantity changed in place, so the level's total follows. */
  void displayedChanged(final Order order, final long before) {
    level(order.side(), keyOf(order.side(), order.price())).displayed += order.remaining() - before;
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

  /** The order a taker reaches next: the front of the best crossing level (FR-3.1, FR-3.3). */
  Order nextToTake(final Side takerSide, final long limit) {
    final Level best = best(opposite(takerSide));
    if (best == null || !crosses(takerSide, limit, best.price)) {
      return null;
    }
    return best.head;
  }

  /** Every order for one participant appended to the caller's space. Still a walk, on purpose. */
  void of(final int participantId, final Scratch into) {
    for (int at = 0; at <= nameMask; at++) {
      final Order order = nameOrders[at];
      if (order != null && order.participantId() == participantId) {
        into.add(order);
      }
    }
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

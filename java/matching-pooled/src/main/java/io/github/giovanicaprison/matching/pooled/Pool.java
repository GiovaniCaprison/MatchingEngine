package io.github.giovanicaprison.matching.pooled;

/**
 * Where orders come from and where they go back to, which is the rung (NFR-4.3).
 *
 * <p>A free list threaded through the orders' own links, so the pool is pointer swaps and holds no
 * structure of its own. It grows by building a fresh order when it runs dry, which prices growth
 * where it belongs: the high-water mark of live orders is paid for once, on the way up, and the
 * steady state after it allocates nothing.
 */
final class Pool {

  private Order free;

  Pool(final int preallocated) {
    for (int i = 0; i < preallocated; i++) {
      release(new Order());
    }
  }

  Order acquire() {
    final Order order = free;
    if (order == null) {
      return new Order();
    }
    free = order.next;
    order.next = null;
    return order;
  }

  /** The order must already be detached from every structure (P-13); the pool checks nothing. */
  void release(final Order order) {
    order.previous = null;
    order.next = free;
    free = order;
  }
}

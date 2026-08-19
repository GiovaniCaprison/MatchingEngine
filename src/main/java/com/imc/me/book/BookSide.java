package com.imc.me.book;

import com.imc.me.domain.OrderSide;

/**
 * One side of the book. Owns its price levels AND its id index, so an order's membership in a side
 * is managed entirely by that side: {@link #addOrder} registers it, {@link #remove} deregisters it.
 * The Matcher mutates a side only through these methods, which is what keeps the id index and the
 * {@code totalQty} invariant (VR-6.1) consistent without the matcher ever touching a map.
 */
public interface BookSide {
  OrderSide side();

  boolean isEmpty();

  Order get(final long orderId);

  PriceLevel bestLevel();

  /**
   * Emits up to {@code maxLevels} of this side's aggregated levels, best price first, into the sink.
   *
   * <p>Emitting rather than returning a collection is what keeps a depth query off the allocator
   * (OOD-9): the caller decides whether anything is materialised, and a counting or top-of-book
   * consumer materialises nothing at all.
   *
   * <p><b>The bound is not optional</b> (OOD-10). An unbounded depth query is O(price levels) in
   * both time and allocation, with the size chosen by whoever is currently spamming the book rather
   * than by the caller — one client asking for depth on a book with 50,000 levels stalls the single
   * writer thread, and every other client with it. That is a self-inflicted denial of service, which
   * is why every real venue's market-data protocol is depth-limited to 5 or 10.
   *
   * @param maxLevels how many levels to emit at most; must be positive. Emits fewer if the side has
   *     fewer, and nothing at all if it is empty.
   */
  void depth(final int maxLevels, final DepthSink sink);

  void addOrder(final Order order);

  void remove(final Order order);
}

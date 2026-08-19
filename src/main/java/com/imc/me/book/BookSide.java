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
   * Emits this side's aggregated levels, best price first, into the sink (OOD-9).
   *
   * <p>Emitting rather than returning a collection is what keeps a depth query off the allocator:
   * the caller decides whether anything is materialised, and a counting or top-of-book consumer
   * materialises nothing at all.
   */
  void depth(final DepthSink sink);

  void addOrder(final Order order);

  void remove(final Order order);
}

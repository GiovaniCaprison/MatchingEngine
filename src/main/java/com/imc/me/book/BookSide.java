package com.imc.me.book;

import com.imc.me.domain.OrderSide;

/**
 * One side of the book. Owns its price levels and its id index, so an order's membership in a side
 * is managed entirely by that side: {@link #addOrder} registers it and {@link #remove} deregisters
 * it. The matcher mutates a side only through these methods, which is what keeps the index and the
 * VR-6.1 total consistent without the matcher ever touching a map.
 */
public interface BookSide {
  OrderSide side();

  boolean isEmpty();

  /**
   * The resting order with this id, or {@code null} if this side is not holding one.
   *
   * <p>{@code null} rather than {@code Optional} because this sits on the cancel and amend path and
   * an {@code Optional} would allocate per lookup (OOD-11). The typed outcome lives a layer up,
   * where {@code cancel} turns a null into {@link com.imc.me.event.result.NotFound}.
   */
  Order get(final long orderId);

  /**
   * The best-priced level on this side: highest bid, or lowest ask.
   *
   * <p>Precondition: the side is non-empty (OOD-16). On an empty side this throws {@link
   * NullPointerException}, which is the contract rather than a bug. Guard with {@link #isEmpty()}
   * as {@code topOfBook} does, or know from context that liquidity exists as the walk does after
   * its own crossing check. An empty side has no best price, so asking for one is a programming
   * error rather than an outcome.
   */
  PriceLevel bestLevel();

  /**
   * Emits up to {@code maxLevels} aggregated levels, best price first, into the sink. Emitting
   * rather than returning a collection keeps the query off the allocator (OOD-9), so a counting or
   * top-of-book consumer materialises nothing.
   *
   * <p>The bound is required rather than defaulted (OOD-10): unbounded, one client asking for depth
   * on a book with 50,000 levels stalls the single writer and everyone else with it. The sink can
   * also end the walk early by returning {@code false}, so a caller whose answer arrives before its
   * bound does pays for neither.
   *
   * @param maxLevels how many levels to emit at most; must be positive. Emits fewer if the side
   *     holds fewer, and nothing if it is empty.
   */
  void depth(final int maxLevels, final DepthSink sink);

  void addOrder(final Order order);

  void remove(final Order order);

  /**
   * Reduces a resting order's quantity in place, keeping its queue position (FR-4.5). Goes through
   * the side rather than the level so the order and the level's total move together (OOD-1).
   *
   * <p>Precondition: the order rests on this side and {@code qty} is less than its remaining
   * quantity. Reducing to zero is a cancel and the boundary refuses it as an amend (OOD-5), so this
   * never has to decide whether to unlink.
   */
  void reduce(final Order order, final long qty);
}

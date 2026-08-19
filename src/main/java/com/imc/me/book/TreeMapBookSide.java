package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A side as a {@link TreeMap} of price to level, with the comparator reversed for bids so that
 * {@code firstEntry} is the best price on either side.
 *
 * <p>Plain {@code TreeMap} and {@code HashMap} rather than concurrent variants, which is a
 * deliberate assertion that exactly one thread mutates a book (OOD-2). This is the correctness
 * reference; the flat-array replacement is OOD-18.
 */
public final class TreeMapBookSide implements BookSide {
  private final OrderSide side;
  private final TreeMap<Long, PriceLevel> levels;
  private final Map<Long, Order> ordersById = new HashMap<>();

  TreeMapBookSide(final OrderSide side) {
    this.side = side;
    this.levels = resolveLevelOrder(side);
  }

  private static TreeMap<Long, PriceLevel> resolveLevelOrder(final OrderSide side) {
    return (side == OrderSide.BUY) ? new TreeMap<>(Comparator.reverseOrder()) : new TreeMap<>();
  }

  public OrderSide side() {
    return side;
  }

  public boolean isEmpty() {
    return levels.isEmpty();
  }

  public Order get(final long orderId) {
    return ordersById.get(orderId);
  }

  public PriceLevel bestLevel() {
    return levels.firstEntry().getValue();
  }

  public void depth(final int maxLevels, final DepthSink sink) {
    int emitted = 0;
    for (final PriceLevel level : levels.values()) {
      if (emitted++ == maxLevels) return;
      sink.onLevel(level.price(), level.totalQty());
    }
  }

  public void addOrder(final Order order) {
    levels.computeIfAbsent(order.price(), LinkedListPriceLevel::new).add(order);
    ordersById.put(order.orderId(), order);
  }

  public void reduce(final Order order, final long qty) {
    levels.get(order.price()).reduce(order, qty);
  }

  /**
   * Removes the order and drops the level with it if that was the last order in it, so no empty
   * level is ever left behind (NFR-3.2).
   *
   * <p>The level is found by {@code order.price()}, which assumes an order's price identifies the
   * level holding it (OOD-14). A market order carries a price sentinel so that it crosses every
   * level without a special case in the walk, and that sentinel is not a real level key. It is
   * harmless only because a market order never rests. Anything else that reaches the book carrying
   * a sentinel price, stop orders being the obvious candidate, has to be given a real price before
   * it rests.
   */
  public void remove(final Order order) {
    final PriceLevel level = levels.get(order.price());
    if (level == null) return;
    level.remove(order);
    ordersById.remove(order.orderId());
    if (level.isEmpty()) levels.remove(order.price());
  }
}

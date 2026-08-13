package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

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

  public void remove(final Order order) {
    final PriceLevel level = levels.get(order.price());
    if (level == null) return;
    level.remove(order);
    ordersById.remove(order.orderId());
    if (level.isEmpty()) levels.remove(order.price());
  }
}

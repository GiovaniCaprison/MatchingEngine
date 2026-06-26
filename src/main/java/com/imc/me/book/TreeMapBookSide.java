package com.imc.me.book;

import com.imc.me.domain.Order;
import com.imc.me.domain.OrderSide;
import com.imc.me.event.dto.Depth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class TreeMapBookSide implements BookSide {
  private final OrderSide side;
  private final TreeMap<Long, PriceLevel> levels;
  private final Map<Long, Order> ordersById = new HashMap<>();

  TreeMapBookSide(OrderSide side) {
    this.side = side;
    this.levels = resolveLevelOrder(side);
  }

  private static TreeMap<Long, PriceLevel> resolveLevelOrder(OrderSide side) {
    return (side == OrderSide.BUY) ? new TreeMap<>(Comparator.reverseOrder()) : new TreeMap<>();
  }

  public OrderSide side() {
    return side;
  }

  public boolean isEmpty() {
    return levels.isEmpty();
  }

  public Order get(long orderId) {
    return ordersById.get(orderId);
  }

  public PriceLevel bestLevel() {
    return levels.firstEntry().getValue();
  }

  public List<Depth.Level> depth() {
    return levels.entrySet().stream()
        .map(e -> new Depth.Level(e.getKey(), e.getValue().totalQty()))
        .toList();
  }

  public void addOrder(Order order) {
    levels.computeIfAbsent(order.price(), LinkedListPriceLevel::new).add(order);
    ordersById.put(order.orderId(), order);
  }

  public void remove(Order order) {
    PriceLevel level = levels.get(order.price());
    if (level == null) return;
    level.remove(order);
    ordersById.remove(order.orderId());
    if (level.isEmpty()) levels.remove(order.price());
  }
}

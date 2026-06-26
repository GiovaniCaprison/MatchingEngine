package com.imc.me.book;

import com.imc.me.domain.Order;
import com.imc.me.domain.OrderSide;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

public final class TreeMapBookSide implements BookSide {
  private final OrderSide side;
  private final TreeMap<Long, PriceLevel> levels;

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

  public PriceLevel levelAt(long price) {
    return levels.get(price);
  }

  public PriceLevel bestLevel() {
    return levels.firstEntry().getValue();
  }

  public List<Long> depth() {
    return levels.values().stream().map(PriceLevel::totalQty).toList();
  }

  public void addOrder(Order order) {
    long price = order.price();
    levels.computeIfAbsent(price, l -> new LinkedListPriceLevel()).add(order);
  }

  public void removeLevelIfEmpty(long price) {
    if (levels.get(price).first() == null) levels.remove(price);
  }
}

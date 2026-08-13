package com.imc.me.book;

import com.imc.me.domain.Order;

public final class LinkedListPriceLevel implements PriceLevel {
  private final long price;
  private long totalQty;
  private Order tail;
  private Order head;

  public LinkedListPriceLevel(final long price) {
    this.price = price;
  }

  public long price() {
    return price;
  }

  public long totalQty() {
    return totalQty;
  }

  public Order first() {
    return head;
  }

  public boolean isEmpty() {
    return head == null;
  }

  public void add(final Order order) {
    order.setNext(null);

    if (head == null) {
      head = order;
    } else {
      tail.setNext(order);
      order.setPrev(tail);
    }

    tail = order;
    totalQty += order.getRemainingQty();
  }

  public void remove(final Order order) {
    Order prev = order.prev(), next = order.next();

    if (prev == null) head = next;
    else prev.setNext(next);

    if (next == null) tail = prev;
    else next.setPrev(prev);

    prev = next = null;
    totalQty -= order.getRemainingQty();
  }

  public void fillFirst(final long qty) {
    head.applyFill(qty);
    totalQty -= qty;
  }

  public void reduce(final Order order, final long qty) {
    order.reduceQty(qty);
    totalQty -= qty;
  }
}

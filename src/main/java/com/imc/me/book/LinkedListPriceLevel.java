package com.imc.me.book;

import com.imc.me.domain.Order;

public final class LinkedListPriceLevel implements PriceLevel {
  private long totalQty;
  private Order tail;
  private Order head;

  public LinkedListPriceLevel() {}

  public long totalQty() {
    return totalQty;
  }

  public Order first() {
    return head;
  }

  public boolean isEmpty() {
    return head == null;
  }

  public void add(Order order) {
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

  public void remove(Order order) {
    Order prev = order.prev(), next = order.next();

    if (prev == null) head = next;
    else prev.setNext(next);

    if (next == null) tail = prev;
    else next.setPrev(prev);

    prev = next = null;
    totalQty -= order.getRemainingQty();
  }

  public void fillFirst(long qty) {
    head.applyFill(qty);
  }

  public void reduce(Order order, long qty) {
    order.reduceQty(qty);
    totalQty -= qty;
  }
}

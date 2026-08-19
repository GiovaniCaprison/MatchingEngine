package com.imc.me.book;

/** A price level as an intrusive doubly-linked FIFO queue. */
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

  /**
   * Appends to the tail, which is what makes arrival order the queue order (FR-3.2).
   *
   * <p>Both links are cleared first so a node's state depends only on this call and not on wherever
   * it was before (OOD-15). That matters for nodes that are re-appended rather than freshly built:
   * a qty-increase amend re-appends the same node (FR-4.4), and a pooled node arrives carrying its
   * previous links.
   */
  public void add(final Order order) {
    order.setNext(null);
    order.setPrev(null);

    if (head == null) {
      head = order;
    } else {
      tail.setNext(order);
      order.setPrev(tail);
    }

    tail = order;
    totalQty += order.remainingQty();
  }

  /**
   * Unlinks the order and detaches it fully: on return it points at nothing and nothing points at
   * it (OOD-15). A removed node with stale links still looks alive, so a later traversal or re-add
   * walks through it into a part of the book that has moved on.
   *
   * <p>The total drops by the order's remaining quantity in the same call that unlinks it, so
   * VR-6.1 is never observably broken (OOD-1).
   */
  public void remove(final Order order) {
    final Order prev = order.prev();
    final Order next = order.next();

    if (prev == null) head = next;
    else prev.setNext(next);

    if (next == null) tail = prev;
    else next.setPrev(prev);

    order.setNext(null);
    order.setPrev(null);

    totalQty -= order.remainingQty();
  }

  public void fillFirst(final Order aggressor, final long qty) {
    head.applyFill(qty);
    aggressor.applyFill(qty);
    totalQty -= qty;
  }

  public void reduce(final Order order, final long qty) {
    order.reduceQty(qty);
    totalQty -= qty;
  }
}

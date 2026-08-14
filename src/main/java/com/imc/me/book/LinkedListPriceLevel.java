package com.imc.me.book;


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
   * <p>Both links are cleared before insertion so that a node's state is a pure function of this
   * call and never of where the node happened to be before it (OOD-15). That matters for orders
   * that are re-appended rather than freshly built: a qty-increase amend (FR-4.4) re-appends the
   * same node, and a pooled node arrives carrying its previous links.
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
   * Unlinks the order and fully detaches it: on return it points at nothing and nothing points at
   * it (OOD-15). Leaving stale {@code next}/{@code prev} on a removed node is the closest thing
   * this code has to a use-after-free — the node looks alive, so a later traversal or re-add walks
   * through it into a part of the book that has moved on.
   *
   * <p>{@code totalQty} is decremented by the order's remaining qty in the same call that unlinks
   * it, which is what keeps VR-6.1 unobservably-broken-free (OOD-1).
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

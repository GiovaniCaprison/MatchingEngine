package com.imc.me.domain;

public final class Order {
  private final long orderId;
  private final long price;
  private final long initialQty;
  private final OrderSide side;
  private final OrderType type;

  private Order next, prev;
  private long filledQty;
  private long withdrawnQty;

  /**
   * The only way to build an order. A static factory rather than a public constructor because the
   * entity is destined for a pool/slab (OOD-18): call sites that say {@code Order.of(...)} keep
   * working when this starts handing back recycled instances, call sites that say {@code new Order}
   * would all have to change.
   *
   * <p>Performs no validation, deliberately — the boundary validates and everything below it trusts
   * (OOD-5). An invalid order must be rejected before it ever reaches here.
   */
  public static Order of(
      final long orderId,
      final long price,
      final long initialQty,
      final OrderSide side,
      final OrderType type) {
    return new Order(orderId, price, initialQty, side, type);
  }

  private Order(
      final long orderId,
      final long price,
      final long initialQty,
      final OrderSide side,
      final OrderType type) {
    this.orderId = orderId;
    this.price = price;
    this.initialQty = initialQty;
    this.side = side;
    this.type = type;

    this.filledQty = 0;
    this.withdrawnQty = 0;
  }

  public long orderId() {
    return orderId;
  }

  public long price() {
    return price;
  }

  public long initialQty() {
    return initialQty;
  }

  public OrderSide side() {
    return side;
  }

  public OrderType type() {
    return type;
  }

  public long filledQty() {
    return filledQty;
  }

  public long withdrawnQty() {
    return withdrawnQty;
  }

  public long getRemainingQty() {
    return initialQty - filledQty - withdrawnQty;
  }

  public Order next() {
    return next;
  }

  public Order prev() {
    return prev;
  }

  public void setNext(Order next) {
    this.next = next;
  }

  public void setPrev(Order prev) {
    this.prev = prev;
  }

  public void applyFill(long qty) {
    filledQty += qty;
  }

  public void reduceQty(long qty) {
    withdrawnQty += qty;
  }
}

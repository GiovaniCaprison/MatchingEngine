package com.imc.me.domain;

public final class Order {
  private final long orderId;
  private final long price;
  private final long initialQty;
  private final OrderSide side;
  private final OrderType type;

  private Order next, prev;
  private long filledQty;

  private Order(
      final long orderId,
      final long price,
      final long initialQty,
      final OrderSide side,
      final OrderType type,
      final Order next,
      final Order prev) {
    this.orderId = orderId;
    this.price = price;
    this.initialQty = initialQty;
    this.side = side;
    this.type = type;

    this.next = next;
    this.prev = prev;

    this.filledQty = 0;
  }

  public long getOrderId() {
    return orderId;
  }

  public long getPrice() {
    return price;
  }

  public long getInitialQty() {
    return initialQty;
  }

  public OrderSide getSide() {
    return side;
  }

  public OrderType getType() {
    return type;
  }

  public long getFilledQty() {
    return filledQty;
  }

  public long getRemainingQty() {
    return initialQty - filledQty;
  }

  public Order getNext() {
    return next;
  }

  public Order getPrev() {
    return prev;
  }

  public void setNext(Order next) {
    this.next = next;
  }

  public void setPrev(Order prev) {
    this.prev = prev;
  }

  public boolean applyFill(long qty) {
    if (qty > getRemainingQty()) {
      throw new IllegalArgumentException(
          String.format(
              "Order {} cannot be filled by more than its remaining quantity", getOrderId()));
    }

    filledQty += qty;
    return filledQty == initialQty;
  }
}

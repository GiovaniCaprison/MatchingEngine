package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.domain.OrderView;

/**
 * The one mutable entity in the system, and the book's intrusive list node.
 *
 * <p>A class rather than a record because it has identity, it is filled in place, and it is
 * destined for a slab (OOD-4, OOD-18). It lives here rather than in {@code domain} because {@code
 * next} and {@code prev} are book mechanics: the order is the node instead of being wrapped in one,
 * which saves an allocation and an indirection per resting order.
 *
 * <p>That location is what lets the mutators be package-private, and package-private is the only
 * enforcement Java offers. Changing {@code filledQty} without changing the enclosing level's {@code
 * totalQty} breaks VR-6.1, so only {@link PriceLevel}, which owns that invariant, may do either
 * (OOD-1). Outside this package nobody can name a mutating method. Read-only consumers get {@link
 * OrderView}.
 */
public final class Order implements OrderView {
  private final long orderId;
  private final long price;
  private final long initialQty;
  private final OrderSide side;
  private final OrderType type;

  private Order next, prev;
  private long filledQty;
  private long withdrawnQty;

  /**
   * The only way to build an order. A factory rather than a constructor so that call sites keep
   * working when this starts handing back recycled instances from a slab (OOD-18).
   *
   * <p>Validates nothing. The boundary validates and everything below it trusts (OOD-5), so an
   * invalid order reaching here is a bug rather than a rejection.
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

  // Order View.

  @Override
  public long orderId() {
    return orderId;
  }

  @Override
  public long price() {
    return price;
  }

  @Override
  public long initialQty() {
    return initialQty;
  }

  @Override
  public OrderSide side() {
    return side;
  }

  @Override
  public OrderType type() {
    return type;
  }

  @Override
  public long filledQty() {
    return filledQty;
  }

  @Override
  public long withdrawnQty() {
    return withdrawnQty;
  }

  @Override
  public long remainingQty() {
    return initialQty - filledQty - withdrawnQty;
  }

  // Book mechanics (OOD-4).

  Order next() {
    return next;
  }

  Order prev() {
    return prev;
  }

  void setNext(final Order next) {
    this.next = next;
  }

  void setPrev(final Order prev) {
    this.prev = prev;
  }

  // Lifecycle mutation (OOD-1).

  /**
   * Records an execution against this order. Call it from the {@link PriceLevel} holding the order,
   * which adjusts its own {@code totalQty} in the same operation. Calling it alone leaves VR-6.1
   * broken.
   */
  void applyFill(final long qty) {
    filledQty += qty;
  }

  /**
   * Records quantity withdrawn by an amend-down. Tracked separately instead of by lowering {@code
   * initialQty}, so {@code initialQty} stays a permanent record of what the client asked for and
   * the audit trail can tell a withdrawal from a fill. Same ownership rule as {@link #applyFill}.
   */
  void reduceQty(final long qty) {
    withdrawnQty += qty;
  }
}

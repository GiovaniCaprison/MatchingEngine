package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.domain.OrderView;

/**
 * The one mutable entity in the system, and the book's intrusive list node.
 *
 * <p><b>Why this is a class and not a record</b> (OOD-4): it has identity (two orders with equal
 * fields are different orders), it is filled in place (a record would allocate a replacement per
 * partial fill, which is unaffordable at target throughput), and it is destined for a pool/slab
 * (OOD-18). {@code record Order} would read more tidily and be wrong.
 *
 * <p><b>Why it lives in {@code book} and not {@code domain}</b> (OOD-4): {@code next}/{@code prev}
 * are book mechanics, not domain vocabulary. The list is <i>intrusive</i> — the order <i>is</i> the
 * node rather than being wrapped in one — which saves an allocation and an indirection per resting
 * order, and later becomes {@code int} slab indices instead of references. That makes this the
 * book's node type, so it belongs in the book's package.
 *
 * <p><b>Why the mutators are package-private</b> (OOD-1): mutation follows ownership. Changing
 * {@code filledQty} without changing the enclosing level's {@code totalQty} breaks VR-6.1, so only
 * the type that owns that invariant — {@link PriceLevel} — may do either. Java has no {@code
 * friend}; package-private is the enforcement mechanism, and it is the whole reason this class
 * moved. Outside {@code com.imc.me.book} nobody can <i>name</i> a mutating method, so nobody can
 * call one. Read-only consumers get {@link OrderView}.
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

  // --- Book mechanics. Package-private: the list is the book's business (OOD-4). ---

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

  // --- Lifecycle mutation. Package-private: callable only by the invariant's owner (OOD-1). ---

  /**
   * Records an execution against this order. Must only be called by the {@link PriceLevel} holding
   * it, which adjusts its own {@code totalQty} in the same operation — calling this alone leaves
   * VR-6.1 broken.
   */
  void applyFill(final long qty) {
    filledQty += qty;
  }

  /**
   * Records a quantity withdrawn by an amend-down. Modelled as withdrawn rather than by lowering
   * {@code initialQty} so that {@code initialQty} stays a permanent record of what the client
   * originally asked for, which the audit trail needs. Same ownership rule as {@link #applyFill}.
   */
  void reduceQty(final long qty) {
    withdrawnQty += qty;
  }
}

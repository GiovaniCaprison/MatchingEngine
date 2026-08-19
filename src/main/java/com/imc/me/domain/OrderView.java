package com.imc.me.domain;

/**
 * A read-only view of an order.
 *
 * <p>The order entity is mutable and confined to {@code com.imc.me.book}, so consumers outside that
 * package are typed against this and cannot name a mutating method (OOD-1, OOD-4).
 *
 * <p>A view rather than a snapshot. The underlying order is still being filled in place by its
 * owner, so two reads can disagree, and a caller that needs a stable picture must copy the fields
 * it cares about. Snapshotting per read would allocate per query (OOD-11), and since the engine is
 * single-writer a reader on the writer thread always sees a coherent order (OOD-2).
 */
public interface OrderView {

  /** Engine-assigned identity, minted by the sequencer (OOD-13). */
  long orderId();

  /** Limit price, as a scaled {@code long} (OOD-12). */
  long price();

  /** The quantity originally requested. Never changes, so it stays valid for the audit trail. */
  long initialQty();

  OrderSide side();

  OrderType type();

  /** Quantity executed so far. */
  long filledQty();

  /** Quantity removed by an amend-down, kept apart from {@link #filledQty} for the audit trail. */
  long withdrawnQty();

  /** {@code initialQty - filledQty - withdrawnQty}. What is still working in the book. */
  long remainingQty();
}

package com.imc.me.domain;

/**
 * A read-only view of an order, for everything outside the book.
 *
 * <p>The order entity is mutable and confined to {@code com.imc.me.book} (OOD-4). Consumers that
 * need to <i>read</i> an order — market data, the order registry, the REST edge, assertions — are
 * typed against this instead. They cannot name a mutating method, so they cannot call one; the
 * confinement is enforced by the compiler rather than by a comment (OOD-1).
 *
 * <p>This is a view, not a snapshot: the underlying order is still being filled in place by its
 * owner, so two reads can disagree. Anything that needs a stable picture must copy the fields it
 * cares about. That is the deliberate trade — a snapshot per read would allocate per query, and the
 * engine is single-writer anyway (OOD-2), so a reader on the writer thread always sees a coherent
 * order.
 *
 * <p>Accessors are record-style ({@code price()}, not {@code getPrice()}) to match the immutable
 * value types at the edge, so a caller moving between an order and a {@code Trade} does not have to
 * change idiom.
 */
public interface OrderView {

  /** Engine-assigned unique id, minted by the sequencer (OOD-13). This is the order's identity. */
  long orderId();

  /** Limit price as a scaled {@code long} — never a {@code double} (OOD-12). */
  long price();

  /** The quantity originally requested. Never changes, so it stays valid for the audit trail. */
  long initialQty();

  OrderSide side();

  OrderType type();

  /** Quantity executed so far. */
  long filledQty();

  /** Quantity removed by amend-down, kept separate from {@link #filledQty} for the audit trail. */
  long withdrawnQty();

  /** {@code initialQty - filledQty - withdrawnQty}. What is still working in the book. */
  long remainingQty();
}

package com.imc.me.book;

/**
 * All the orders resting at one price, in arrival order, with a running total of their quantity.
 */
public interface PriceLevel {
  long price();

  long totalQty();

  /**
   * The order at the front of the queue, which is the next one to be filled (FR-3.2).
   *
   * <p>Returns {@code null} on an empty level. A level only exists while it holds at least one
   * order (NFR-3.2), so inside the book this is always non-null; the null case is reachable only
   * for a level held directly, as tests do.
   */
  Order first();

  boolean isEmpty();

  void add(final Order order);

  void remove(final Order order);

  /**
   * Applies one execution of {@code qty} between the aggressor and the order at the front of this
   * level, moving both orders' filled quantities and this level's total in a single operation.
   *
   * <p>Both sides of the execution are mutated here, by the level, on purpose (OOD-1). A trade is
   * one transition of three pieces of state and the level owns the invariant spanning them
   * (VR-6.1), so it performs all three. Letting the matcher fill the aggressor while the level
   * fills the resting order is the split that lets a caller do one half and leave the book
   * inconsistent.
   *
   * <p>Precondition (OOD-16): the level is non-empty and {@code qty} is no greater than either
   * side's remaining quantity. Unchecked, because the caller chose the quantity and re-deriving it
   * here costs a branch per execution on the hottest path in the system.
   *
   * <p>A fully filled resting order is left linked. The caller unlinks it through {@link
   * BookSide#remove} so the id index and the level move together.
   */
  void fillFirst(final Order aggressor, final long qty);

  void reduce(final Order order, final long qty);
}

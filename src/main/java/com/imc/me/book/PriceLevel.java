package com.imc.me.book;


public interface PriceLevel {
  long price();

  long totalQty();

  /**
   * The order at the front of the FIFO queue — the next one to be filled (FR-3.2).
   *
   * <p>Returns {@code null} on an empty level. A level only exists while it holds at least one order
   * (the side removes it otherwise, NFR-3.2), so within the book a non-null result is guaranteed;
   * the null case is reachable only for a level held directly, as tests do.
   */
  Order first();

  boolean isEmpty();

  void add(final Order order);

  void remove(final Order order);

  /**
   * Applies one execution of {@code qty} between the aggressor and the order at the front of this
   * level, advancing both orders' filled quantities and this level's {@code totalQty} in a single
   * operation.
   *
   * <p>Both sides of the execution are mutated here, by the level, on purpose (OOD-1). A trade is
   * one atomic transition of three pieces of state, and the level owns the invariant that spans
   * them (VR-6.1), so it performs all three. The alternative — letting the {@code Matcher} fill the
   * aggressor while the level fills the resting order — is precisely the split that lets a caller
   * perform one half and leave the book inconsistent. It is also why the order entity's mutators
   * are package-private: outside this package, this method is the <i>only</i> way to fill anything.
   *
   * <p>Precondition (OOD-16): the level is non-empty and {@code qty} is no greater than either
   * side's remaining quantity. Not checked — the caller decided the quantity, and re-deriving it
   * here would cost a branch per execution on the hottest path in the system.
   *
   * <p>Does <i>not</i> unlink a fully-filled resting order; the caller does that via {@link
   * BookSide#remove}, so that the id index and the level are updated together.
   */
  void fillFirst(final Order aggressor, final long qty);

  void reduce(final Order order, final long qty);
}

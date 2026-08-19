package com.imc.me.book;

/**
 * Where the book puts aggregated price levels, and the read-side counterpart to {@link
 * com.imc.me.matching.TradeSink} (OOD-9).
 *
 * <p>Levels arrive best price first, so the first callback is always the top of book. A consumer
 * that only wants the top can stop caring after one call, and a five-deep consumer never sees the
 * sixth level (see the bound on {@link BookSide#depth}).
 */
public interface DepthSink {

  /**
   * One aggregated price level.
   *
   * @param price scaled price of the level (OOD-12)
   * @param qty sum of the remaining quantity of every order resting at that price, read straight
   *     off the level's running total. That is what makes depth linear in levels rather than in
   *     orders, and what makes VR-6.1 a one-line assertion.
   */
  void onLevel(final long price, final long qty);
}

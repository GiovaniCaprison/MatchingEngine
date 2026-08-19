package com.imc.me.book;

/**
 * Where the book puts aggregated price levels, and the read-side counterpart to {@link
 * com.imc.me.matching.TradeSink} (OOD-9).
 *
 * <p>Levels arrive best price first, so the first callback is always the top of book.
 */
public interface DepthSink {

  /**
   * One aggregated price level.
   *
   * <p>Return {@code false} to end the walk. A consumer that has seen everything it needs can stop
   * the book rather than being handed levels it will discard, which is what keeps a walk
   * proportional to what the caller wanted rather than to the size of the side. A five-deep
   * snapshot and a fillability probe are both bounded by their own answer this way (OOD-10).
   *
   * @param price scaled price of the level (OOD-12)
   * @param qty sum of the remaining quantity of every order resting at that price, read straight
   *     off the level's running total. That is what makes depth linear in levels rather than in
   *     orders.
   * @return {@code true} to continue, {@code false} to stop
   */
  boolean onLevel(final long price, final long qty);
}

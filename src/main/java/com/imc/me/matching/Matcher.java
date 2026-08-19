package com.imc.me.matching;

import com.imc.me.book.BookSide;
import com.imc.me.book.Order;

/**
 * The matching algorithm, as a strategy the book drives over the opposing side.
 *
 * <p>Price-time versus pro-rata is a real venue-level variation, which is what justifies the
 * abstraction existing (OOD-17). There is no implementation per order type: the walk is identical
 * for all five, and only the gate before it and the remainder policy after it differ (OOD-8).
 *
 * <p>Emits into a {@link TradeSink} rather than returning a collection (OOD-9), and mutates the
 * book only through {@link BookSide} and price level methods. Outside {@code com.imc.me.book} it
 * cannot reach an order's lifecycle fields at all (OOD-1).
 */
public interface Matcher {

  /**
   * Walks the opposing side from its best price inward, consuming liquidity while the prices cross,
   * FIFO within each level, emitting one callback per execution at the resting order's price
   * (FR-3.5).
   *
   * <p>Mutates both the aggressor and the book. On return, {@code aggressor.remainingQty()} is
   * whatever could not be filled, and the caller applies the per-type remainder policy.
   */
  void match(final Order aggressor, final BookSide opposing, final TradeSink sink);

  /**
   * How much of {@code aggressor} the opposing side could fill right now, without mutating
   * anything.
   *
   * <p>FOK is why this exists (FR-2.5). Fill-or-kill cannot be a remainder policy, because by the
   * time the remainder is known the executions have happened and there is no un-trading, so the
   * decision has to be taken before the walk. It lives here beside {@link #match} rather than in
   * the book so there is one crossing check instead of two that can drift apart.
   *
   * <p>Post-only uses it too (FR-2.6): a non-zero result means the order would take liquidity and
   * has to be refused.
   *
   * @return the fillable quantity, capped at {@code aggressor.remainingQty()}
   */
  long fillableQty(final Order aggressor, final BookSide opposing);
}

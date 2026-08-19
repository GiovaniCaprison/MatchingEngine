package com.imc.me.matching;

import com.imc.me.book.BookSide;
import com.imc.me.book.Order;

/**
 * The matching algorithm, as a strategy the book drives over the opposing side.
 *
 * <p>The interface is deliberately tiny and deliberately type-agnostic. Price-time and pro-rata are
 * a real venue-level variation, which is what justifies the abstraction existing at all (OOD-17) —
 * but there is <b>no</b> implementation per order type. The walk is identical for LIMIT, MARKET,
 * IOC, FOK and POST; only the gate before it and the remainder policy after it differ, and both of
 * those live in the book as switches on data (OOD-8). Five implementations behind this interface
 * would put a megamorphic call site in the hottest loop in the system and stop the JIT inlining
 * through it.
 *
 * <p>Emits into a {@link TradeSink} rather than returning a collection (OOD-9), and mutates the book
 * only through {@link BookSide} and {@code PriceLevel} methods, never by touching an order's
 * lifecycle fields directly — outside {@code com.imc.me.book} it cannot (OOD-1).
 */
public interface Matcher {

  /**
   * Walks the opposing side from its best price inward, consuming liquidity while the prices cross,
   * FIFO within each level, emitting one callback per execution at the resting order's price
   * (FR-3.5).
   *
   * <p>Mutates both the aggressor and the book. On return, {@code aggressor.remainingQty()} is
   * whatever could not be filled — the caller applies the per-type remainder policy (OOD-8).
   */
  void match(final Order aggressor, final BookSide opposing, final TradeSink sink);

  /**
   * How much of {@code aggressor} the opposing side could fill right now, without mutating
   * anything.
   *
   * <p>This exists for FOK (FR-2.5), and FOK is why it has to. Fill-or-kill cannot be expressed as a
   * remainder policy: by the time the remainder is known you have already traded, and there is no
   * un-trading. So the decision has to be taken before the walk, from the same crossing logic the
   * walk uses — which is exactly why it belongs here, beside {@link #match}, rather than in the book.
   * Two copies of "do these prices cross" that can drift apart is the bug this placement prevents.
   *
   * <p>Also serves POST-only (FR-2.6), which needs to know whether it would cross at all: a non-zero
   * result means it would take liquidity and must be rejected.
   *
   * @return the fillable quantity, capped at {@code aggressor.remainingQty()}
   */
  long fillableQty(final Order aggressor, final BookSide opposing);
}

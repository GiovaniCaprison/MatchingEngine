package com.imc.me.book;

import com.imc.me.event.result.AmendOutcome;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.SubmitOutcome;
import com.imc.me.matching.TradeSink;

/**
 * The mutating half of a book. Exactly one thread ever holds one of these (OOD-2).
 *
 * <p>Every method assumes its input was validated at the boundary and never re-checks (OOD-5),
 * which is what makes "was the book modified?" answerable: a rejected command never reaches this
 * interface.
 */
public interface OrderBookWriter {

  /**
   * Matches the order against the opposing side, then applies its type's remainder policy.
   *
   * <p>The executions do not come back (OOD-9). Submission produces zero to many of them depending
   * on how much liquidity it crosses, so trades go to the sink as they happen and the boundary
   * decides whether to collect them for a client or publish them and allocate nothing. What comes
   * back is the terminal state, as an enum constant.
   */
  SubmitOutcome submit(final Order order, final TradeSink sink);

  /**
   * Amends a resting order to a new quantity and price.
   *
   * <p>Carries the full new state rather than a delta, so "unchanged" is never ambiguous with "set
   * to zero" and no per-field sentinel is needed. Takes a sink because a reprice can cross the
   * spread and execute, so an amend is not always a book-only operation.
   */
  AmendOutcome amend(
      final long orderId, final long newQty, final long newPrice, final TradeSink sink);

  CancelResult cancel(final long orderId);
}

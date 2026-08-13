package com.imc.me.book;

import com.imc.me.event.result.AmendOutcome;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.SubmitOutcome;
import com.imc.me.event.sink.TradeEventSink;

/**
 * The mutating half of a book. Exactly one thread ever holds one of these (OOD-2).
 *
 * <p>Every method here assumes its input has already been validated at the boundary and never
 * re-checks (OOD-5), which is what makes "was the book modified?" answerable: a rejected command
 * never reaches this interface at all.
 */
public interface OrderBookWriter {

  /**
   * Matches the order against the opposing side, then applies its type's remainder policy.
   *
   * <p>The executions do not come back (OOD-9). Submission is the one command that produces a
   * <i>stream</i> of results — zero to many, depending on how much liquidity it crosses — and a
   * returned collection is the one shape that cannot be made allocation-free. Trades go to the sink
   * as they happen; the boundary decides whether to collect them into a {@link
   * com.imc.me.event.result.SubmitResult} for a client, or publish them and allocate nothing.
   *
   * <p>What does come back is the terminal state, as an enum constant — typed rather than a boolean
   * (OOD-6), and free rather than an allocation (OOD-11).
   *
   * <p>Assumes the order is already valid: the boundary validates and everything below it trusts
   * (OOD-5). Submitting an off-tick or non-positive-quantity order here is a programming error, not
   * a rejection.
   */
  SubmitOutcome submit(final Order order, final TradeEventSink sink);

  /**
   * Amends a resting order to a new quantity and price.
   *
   * <p>Carries the full new state rather than a delta, so "unchanged" is never ambiguous with "set to
   * zero" and no per-field sentinel is needed. Takes a sink because a reprice can cross the spread and
   * execute — an amend is not necessarily a book-only operation.
   *
   * <p>Assumes both values are already validated (OOD-5). In particular a non-positive quantity is
   * rejected at the boundary, so this method never has to decide whether an amend-to-zero means
   * cancel.
   */
  AmendOutcome amend(
      final long orderId, final long newQty, final long newPrice, final TradeEventSink sink);

  CancelResult cancel(final long orderId);
}

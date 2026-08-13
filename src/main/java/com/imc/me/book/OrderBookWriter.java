package com.imc.me.book;

import com.imc.me.event.result.AmendResult;
import com.imc.me.event.result.CancelResult;
import com.imc.me.matching.TradeSink;

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
   * <p>Returns nothing on purpose (OOD-9). Submission is the one command that produces a <i>stream</i>
   * of results — zero to many executions, depending on how much liquidity it crosses — and a
   * returned collection is the one shape that cannot be made allocation-free. Trades go to the sink
   * as they happen; the boundary decides whether to collect them into a {@link
   * com.imc.me.event.result.SubmitResult} for a client, or publish them and allocate nothing.
   *
   * <p>Assumes the order is already valid: the boundary validates and everything below it trusts
   * (OOD-5). Submitting an off-tick or non-positive-quantity order here is a programming error, not
   * a rejection.
   */
  void submit(final Order order, final TradeSink sink);

  AmendResult amend(final long orderId);

  CancelResult cancel(final long orderId);
}

package com.imc.me.event.result;

import com.imc.me.domain.Trade;
import com.imc.me.util.Seq;

/**
 * The order was accepted, along with whatever it executed on entry and what became of it.
 *
 * @param clientOrderId the client's reference, echoed verbatim (API-1.3)
 * @param orderId the engine uid the client must use to cancel or amend (FR-1.3)
 * @param outcome what happened to it -- resting, filled, or remainder cancelled
 * @param fills the executions, materialised here because a request/response client is about to
 *     serialise them anyway (OOD-3). A publishing consumer takes them from the sink instead and
 *     allocates nothing.
 */
public record Accepted(
    long clientOrderId, long orderId, SubmitOutcome outcome, Seq<Trade> fills)
    implements SubmitResult, AmendResult {

  /** Accepted with no executions — it crossed nothing and is now resting. */
  public static Accepted resting(final long clientOrderId, final long orderId) {
    return new Accepted(clientOrderId, orderId, SubmitOutcome.RESTED, Seq.empty());
  }
}

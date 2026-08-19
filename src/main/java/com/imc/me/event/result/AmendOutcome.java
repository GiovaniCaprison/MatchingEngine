package com.imc.me.event.result;

/**
 * What happened to an amend, as a single allocation-free value.
 *
 * <p>Same split as {@link SubmitOutcome}: this is the core's answer, and {@link AmendResult} is the
 * edge DTO built from it (OOD-3).
 *
 * <p>The distinction that earns these constants their existence is <b>queue priority</b>. A client
 * that amends needs to know whether it kept its place in line, because that is worth more than the
 * amend itself in a fast market — and it is not derivable from the request alone, since it depends on
 * which fields changed and in which direction. Returning a bare "ok" would hide the single most
 * important consequence of the operation.
 */
public enum AmendOutcome {

  /** No live order with that id. Not an error (OOD-6) — it raced with a fill or a cancel. */
  NOT_FOUND,

  /**
   * Quantity was reduced in place and <b>time priority was kept</b> (FR-4.5).
   *
   * <p>Safe only because reducing takes nothing from anyone else: every order queued behind this one
   * is strictly better off, so there is no fairness argument for making it re-queue.
   */
  REDUCED_KEPT_PRIORITY,

  /**
   * The order was unlinked and re-appended at the tail, so <b>time priority was lost</b> (FR-4.4).
   *
   * <p>A quantity increase or a reprice both land here. Increasing is asking for more than the queue
   * position was granted for; repricing is a different queue entirely. Either way, keeping priority
   * would let a client hold a good position with a token order and inflate it on seeing flow, which
   * is precisely the abuse price-time priority exists to prevent.
   */
  REQUEUED_LOST_PRIORITY,

  /**
   * The amend repriced the order aggressively enough to cross, and it fully executed. Nothing rests.
   */
  FILLED_ON_AMEND,

  /**
   * The amend repriced the order across the spread and its type forbade resting the remainder, so
   * what did not execute was cancelled. Reachable for MARKET/IOC orders only.
   */
  REMAINDER_CANCELLED_ON_AMEND,

  /**
   * The amend was refused because the new price would have taken liquidity, which the order's type
   * forbids (POST-only, FR-2.6).
   *
   * <p><b>The original order is untouched and still resting.</b> That is the whole reason this
   * constant exists rather than being folded into {@link #NOT_FOUND}: the gate runs before the
   * original is unlinked (API-8.2), because silently cancelling an order the client asked to
   * <i>keep</i> is the worst possible reading of "your amend was rejected".
   */
  REJECTED_WOULD_CROSS
}

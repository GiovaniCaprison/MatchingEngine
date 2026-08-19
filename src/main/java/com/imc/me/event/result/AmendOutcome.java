package com.imc.me.event.result;

/**
 * What happened to an amend, as a single allocation-free value. Same core and edge split as {@link
 * SubmitOutcome} (OOD-3).
 *
 * <p>These constants exist to report queue priority. A client that amends needs to know whether it
 * kept its place in line, which is worth more than the amend itself in a fast market and is not
 * derivable from the request, since it depends on which fields changed and in which direction.
 */
public enum AmendOutcome {

  /** No live order with that id. It raced with a fill or a cancel (OOD-6). */
  NOT_FOUND,

  /**
   * Quantity was reduced in place and time priority was kept (FR-4.5). Safe because reducing takes
   * nothing from anyone else: every order queued behind is strictly better off.
   */
  REDUCED_KEPT_PRIORITY,

  /**
   * The order was unlinked and re-appended at the tail, so time priority was lost (FR-4.4).
   *
   * <p>A quantity increase or a reprice both land here. Increasing asks for more than the queue
   * position was granted for, and repricing is a different queue entirely. Keeping priority through
   * either would let a client hold a good position with a token order and inflate it on seeing
   * flow.
   */
  REQUEUED_LOST_PRIORITY,

  /** The amend repriced the order aggressively enough to cross, and it fully executed. */
  FILLED_ON_AMEND,

  /**
   * The amend repriced the order across the spread and its type forbade resting the remainder, so
   * what did not execute was cancelled. MARKET and IOC only.
   */
  REMAINDER_CANCELLED_ON_AMEND,

  /**
   * The amend was refused because the new price would have taken liquidity, which the order's type
   * forbids (POST-only, FR-2.6).
   *
   * <p>The original order is untouched and still resting, which is why this is separate from {@link
   * #NOT_FOUND}. The gate runs before the original is unlinked (API-8.2), since silently cancelling
   * an order the client asked to keep is the worst reading of "your amend was rejected".
   */
  REJECTED_WOULD_CROSS
}

package com.imc.me.event.result;

/**
 * What happened to a submitted order, as a single allocation-free value.
 *
 * <p>This is the core's answer, and {@link SubmitResult} is the edge DTO built from it plus the
 * trades a collecting sink gathered (OOD-3). An enum constant allocates nothing, so the write path
 * stays inside its budget while still returning something typed (OOD-11, OOD-6).
 *
 * <p>The executions are not here. A submission produces zero to many of them, so the stream goes to
 * a {@link com.imc.me.matching.TradeSink} and only the terminal state comes back (OOD-9).
 *
 * <p>Every constant is reachable from exactly one arm of the gate or remainder switch in the book,
 * so adding one here fails compilation at each decision point until it is handled.
 */
public enum SubmitOutcome {

  /** Fully executed on entry. Nothing rests and the order is terminal. */
  FILLED,

  /**
   * Some or none executed, and the remainder is now resting (FR-2.1). LIMIT, and POST once it has
   * passed the gate.
   */
  RESTED,

  /**
   * Some or none executed, and the remainder was cancelled rather than rested (FR-2.3, FR-2.4).
   * MARKET and IOC. The distinction from {@link #FILLED} matters to the client, which asked for
   * more than the book could give.
   */
  REMAINDER_CANCELLED,

  /**
   * Nothing executed and nothing rests, because the order could not be filled in full (FR-2.5). FOK
   * only, and decided before any execution, so the book is untouched.
   */
  KILLED,

  /**
   * Nothing executed and nothing rests, because the order would have taken liquidity (FR-2.6). POST
   * only, and like {@link #KILLED} decided before the walk.
   */
  REJECTED_WOULD_CROSS
}

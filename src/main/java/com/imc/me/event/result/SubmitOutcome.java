package com.imc.me.event.result;

/**
 * What happened to a submitted order, as a single allocation-free value.
 *
 * <p><b>Why an enum and not one of the sealed result records.</b> The two coexist on purpose and are
 * not redundant (OOD-3):
 *
 * <ul>
 *   <li>This is the <b>core's</b> answer. An enum constant allocates nothing, so the write path stays
 *       within its allocation budget (OOD-11) while still returning a typed outcome rather than a
 *       boolean (OOD-6).
 *   <li>{@link SubmitResult} is the <b>edge's</b> answer, built from this outcome plus the trades a
 *       collecting sink gathered, for a client that wants a request/response DTO.
 * </ul>
 *
 * <p>Note what is <i>not</i> here: the executions. A submission produces zero to many trades, and a
 * returned collection is the one shape that cannot be made allocation-free — so the stream goes to a
 * {@link com.imc.me.matching.TradeSink} and only the terminal state comes back (OOD-9).
 *
 * <p>Every constant is reachable from exactly one arm of the gate or remainder switch in the book, so
 * adding one here fails compilation at each decision point until it is handled — which is precisely
 * how you find them all (OOD-8).
 */
public enum SubmitOutcome {

  /** Fully executed on entry. Nothing rests; the order is terminal. */
  FILLED,

  /**
   * Some or none executed, and the remainder is now resting in the book (FR-2.1). LIMIT, and POST
   * once it has passed the gate.
   */
  RESTED,

  /**
   * Some or none executed, and the remainder was cancelled rather than rested (FR-2.3, FR-2.4).
   * MARKET and IOC. The distinction from {@link #FILLED} matters to the client: it asked for more
   * than the book could give.
   */
  REMAINDER_CANCELLED,

  /**
   * Nothing executed and nothing rests, because the order could not be filled in full (FR-2.5). FOK
   * only. Decided <i>before</i> any execution, so this outcome leaves the book untouched.
   */
  KILLED,

  /**
   * Nothing executed and nothing rests, because the order would have taken liquidity (FR-2.6). POST
   * only. Like {@link #KILLED}, decided before the walk, so the book is untouched.
   */
  REJECTED_WOULD_CROSS
}

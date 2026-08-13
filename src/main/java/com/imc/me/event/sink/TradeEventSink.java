package com.imc.me.event.sink;

/**
 * Where the <b>book</b> puts sequenced trade events — the engine's outbound stream.
 *
 * <p><b>Why this is separate from {@link com.imc.me.matching.TradeSink}.</b> They look alike and
 * describe different jobs, which is the point:
 *
 * <ul>
 *   <li>{@code TradeSink} is what the <b>matcher</b> emits into: "these two orders executed, this much,
 *       at this price." The matcher knows about crossing prices and queues. It knows nothing about the
 *       engine's event stream, and giving it a sequencer would be handing the algorithm a
 *       responsibility that has nothing to do with matching.
 *   <li>{@code TradeEventSink} is what the <b>book</b> emits into, after stamping each execution with
 *       its position in the total order (OOD-13). That stamp is what makes the stream replayable and
 *       auditable.
 * </ul>
 *
 * <p>So the pipeline reads: the matcher reports <i>executions</i>, and the book turns them into
 * <i>events</i>. The book owns the sequencer because the book is the single writer, and the sequence
 * number has to be assigned by whoever imposes the order — not by whoever happens to consume it.
 * Assigning it at the consumer would make two consumers of the same run disagree.
 *
 * <p>Still primitives, so a publishing implementation allocates nothing (OOD-9).
 */
public interface TradeEventSink {

  /**
   * One sequenced execution, at the resting order's price (FR-3.5).
   *
   * @param sequence position in the engine's total order — monotonic, gap-free, never reused
   * @param aggressorId uid of the incoming order that crossed
   * @param restingId uid of the order that was already in the book
   * @param price scaled price of the execution (OOD-12)
   * @param qty executed quantity, always positive
   */
  void onTrade(
      final long sequence,
      final long aggressorId,
      final long restingId,
      final long price,
      final long qty);
}

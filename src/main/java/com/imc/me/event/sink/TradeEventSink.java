package com.imc.me.event.sink;

/**
 * Where the book puts sequenced trade events, which is the engine's outbound stream.
 *
 * <p>Separate from {@link com.imc.me.matching.TradeSink} because they carry different things. The
 * matcher and the book report executions: these two orders traded, this much, at this price. The
 * engine turns those into events by stamping each one with its position in the total order
 * (OOD-13), and that stamp is what makes the stream replayable and auditable.
 *
 * <p>The engine stamps rather than the book, so that swapping one book implementation for another
 * cannot change the numbering. Two implementations of the same behaviour have to produce identical
 * output for a comparison between them to mean anything, and sequencing is the one part of that
 * output a data structure has no business deciding.
 *
 * <p>Still primitives, so a publishing implementation allocates nothing (OOD-9).
 */
public interface TradeEventSink {

  /**
   * One sequenced execution, at the resting order's price (FR-3.5).
   *
   * @param sequence position in the engine's total order: monotonic, gap-free, never reused
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

package com.imc.me.matching;

/**
 * Where the matcher puts trades. The core's outbound port for executions (OOD-9).
 *
 * <p><b>Why a sink instead of a returned collection.</b> A returned {@code List<Trade>} forces an
 * allocation the caller cannot decline, at a size not known until the walk finishes — so it is a
 * growable structure plus copying, per aggressing order, on the hottest path in the system. There is
 * no way to make a returned collection allocation-free, which is why this is a design decision made
 * when the signature is written rather than a profiler finding later (OOD-11).
 *
 * <p><b>Why primitives instead of a {@code Trade}.</b> Passing a {@code Trade} would allocate one
 * object per execution — a sweep across five levels would allocate five objects whether or not the
 * consumer wants them. Flat primitives let a publishing sink write straight into a ring buffer or a
 * byte buffer and allocate <i>nothing</i>. The {@code Trade} record still exists and is still the
 * right type: it is an <b>edge</b> type, materialised by {@link
 * com.imc.me.event.sink.CollectingTradeSink} when something is about to serialise or assert on it.
 *
 * <p>The secondary benefit is streaming semantics: a consumer can act on the first execution before
 * the last one exists, which is what a real outbound market-data feed needs.
 *
 * <p>Implementations are called on the single writer thread (OOD-2) and must not block, allocate
 * unboundedly, or throw. A sink that throws mid-walk leaves the book correctly updated but the
 * outbound record incomplete, which is unrecoverable — validate the consumer, not the walk.
 */
public interface TradeSink {

  /**
   * One execution. Emitted at the <b>resting</b> order's price, because price improvement accrues to
   * the aggressor (FR-3.5).
   *
   * @param aggressorId uid of the incoming order that crossed
   * @param restingId uid of the order that was already in the book
   * @param price scaled price of the execution — the resting order's price (OOD-12)
   * @param qty executed quantity, always positive
   */
  void onTrade(final long aggressorId, final long restingId, final long price, final long qty);
}

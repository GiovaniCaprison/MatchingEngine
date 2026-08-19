package com.imc.me.matching;

/**
 * Where the matcher puts executions, and the core's outbound port for them (OOD-9).
 *
 * <p>Flat primitives rather than a {@code Trade} per callback, so a publishing sink can write
 * straight into a ring buffer and allocate nothing (OOD-11). The {@code Trade} record is still the
 * right type at the edge, materialised by {@link com.imc.me.event.sink.CollectingTradeSink} when
 * something is about to serialise or assert on it.
 *
 * <p>Sinks also give streaming semantics: a consumer can act on the first execution before the last
 * one exists, which is what a real outbound feed needs.
 *
 * <p>Implementations are called on the single writer thread (OOD-2) and must not block, allocate
 * unboundedly, or throw. A sink that throws mid-walk leaves the book correctly updated and the
 * outbound record incomplete, and there is no way back from that, so validate the consumer rather
 * than the walk.
 */
public interface TradeSink {

  /**
   * One execution, at the resting order's price, because price improvement accrues to the aggressor
   * (FR-3.5).
   *
   * @param aggressorId uid of the incoming order that crossed
   * @param restingId uid of the order that was already in the book
   * @param price scaled price of the execution, which is the resting order's price (OOD-12)
   * @param qty executed quantity, always positive
   */
  void onTrade(final long aggressorId, final long restingId, final long price, final long qty);
}

package com.imc.me.event.sink;

import com.imc.me.event.dto.Status;
import com.imc.me.event.result.RejectReason;

/**
 * A consumer of everything the engine does (API-7.1, FR-6.1). This is the outbound stream a real
 * deployment reads: a market data publisher, a drop-copy feed, a risk system, a persistence
 * journal.
 *
 * <p>Push rather than poll, in primitives (OOD-9). A poller cannot see events it missed between
 * polls, and a returned batch cannot be allocation-free.
 *
 * <p>Every method has an empty default, so a consumer implements only the events it wants. A risk
 * system that only cares about fills should not have to write four empty methods to say so.
 *
 * <p>Called synchronously on the writer thread (OOD-2), inside the command being processed. An
 * implementation that blocks, allocates unboundedly or throws will stall or corrupt the engine: a
 * listener that throws mid-command leaves the book correctly updated and the outbound record
 * incomplete, and there is no way back from that. Consumers that need to do real work hand off to
 * their own queue.
 */
public interface EngineListener extends TradeEventSink {

  /** The order passed validation and was admitted with this engine uid. */
  default void onAccepted(final long clientOrderId, final long orderId) {}

  /** The order was refused. The book is unchanged (API-8.2). */
  default void onRejected(
      final long clientOrderId, final long orderId, final RejectReason reason) {}

  /** The order reached a terminal state and will produce no further events. */
  default void onTerminal(final long orderId, final Status status) {}

  /**
   * One sequenced execution. Inherited from {@link TradeEventSink} so a listener is also a sink.
   */
  @Override
  default void onTrade(
      final long sequence,
      final long aggressorId,
      final long restingId,
      final long price,
      final long qty) {}
}

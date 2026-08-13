package com.imc.me.event.sink;

import com.imc.me.domain.Trade;
import com.imc.me.matching.TradeSink;
import com.imc.me.util.Seq;

/**
 * The edge adapter: turns the core's primitive trade callbacks into immutable {@link Trade} values.
 *
 * <p>This is where the allocation the core refuses to do gets done, deliberately and at the edge's
 * expense (OOD-3). It is correct here — a caller using this is about to serialise the trades to a
 * client, hand them to an assertion, or put them in a request/response DTO, so the objects are
 * needed anyway. It is <i>not</i> correct on a publishing path: a sink writing into a ring buffer
 * implements {@link TradeSink} directly and allocates nothing.
 *
 * <p>Note the direction of the dependency: the edge depends on the core's port, never the reverse
 * (OOD-3). {@code matching} does not know this class exists.
 *
 * <p>Single-use and not thread-safe, like everything on the writer thread (OOD-2). Build one per
 * command, drain it with {@link #fills()}, discard it.
 */
public final class CollectingTradeSink implements TradeSink {

  private final Seq.Builder<Trade> fills;
  private long executedQty;

  public CollectingTradeSink() {
    this.fills = Seq.builder();
  }

  @Override
  public void onTrade(
      final long aggressorId, final long restingId, final long price, final long qty) {
    fills.add(new Trade(aggressorId, restingId, price, qty));
    executedQty += qty;
  }

  /** Number of executions collected so far. */
  public int count() {
    return fills.size();
  }

  /** Total quantity executed across every collected trade. */
  public long executedQty() {
    return executedQty;
  }

  /** The collected trades, in execution order. Spends the underlying builder. */
  public Seq<Trade> fills() {
    return fills.build();
  }
}

package com.imc.me.matching;

import com.imc.me.book.BookSide;
import com.imc.me.book.DepthSink;
import com.imc.me.book.Order;
import com.imc.me.book.PriceLevel;
import com.imc.me.domain.OrderSide;

/**
 * Price-then-time priority: best price first, FIFO within a price (FR-3.1, FR-3.2).
 *
 * <p>Stateful, and not thread-safe, like everything on the writer thread (OOD-2). The one field is
 * a reused probe, so a fillability check costs no allocation of its own.
 */
public final class PriceTimeMatcher implements Matcher {

  private final FillableProbe probe = new FillableProbe();

  /**
   * The walk. Two nested loops and no knowledge of order type at all (OOD-8): the outer one moves
   * outward through price levels while they cross, the inner one consumes a level in arrival order.
   *
   * <p>A market order needs no special case here because it carries a price sentinel that crosses
   * everything (see {@link com.imc.me.util.Prices}), which is what keeps the type out of the hot
   * loop.
   */
  @Override
  public void match(final Order aggressor, final BookSide opposing, final TradeSink sink) {
    while (aggressor.remainingQty() > 0 && !opposing.isEmpty()) {
      final PriceLevel best = opposing.bestLevel();

      // Levels come out best price first, so the first one that does not cross means none deeper
      // will.
      if (!crosses(aggressor, best.price())) return;

      while (aggressor.remainingQty() > 0 && !best.isEmpty()) {
        final Order resting = best.first();
        final long qty =
            aggressor.remainingQty() < resting.remainingQty()
                ? aggressor.remainingQty()
                : resting.remainingQty();

        // One call moves the resting order, the aggressor and the level's total together, so VR-6.1
        // is never observably broken (OOD-1). The trade is reported at the resting order's price,
        // because price improvement accrues to the aggressor (FR-3.5).
        best.fillFirst(aggressor, qty);
        sink.onTrade(aggressor.orderId(), resting.orderId(), best.price(), qty);

        // Through the side rather than the level, so the id index and the level are updated
        // together
        // and an emptied level is dropped rather than left behind (NFR-3.2).
        if (resting.remainingQty() == 0) opposing.remove(resting);
      }
    }
  }

  /**
   * The same crossing logic as {@link #match}, read-only.
   *
   * <p>Walks aggregated levels rather than individual orders, which is sound because a level's
   * total is the sum of the remaining quantity resting in it (VR-6.1). That makes the probe linear
   * in crossing levels rather than in orders.
   *
   * <p>Known cost: {@link BookSide#depth} emits every level up to its bound, so the probe is handed
   * levels that do not cross and discards them. It stops accumulating, but it cannot stop the walk,
   * because the side exposes no way to iterate levels while a condition holds. On a wide book that
   * is real waste on the FOK and POST gate path, and closing it means giving {@code BookSide} a
   * predicated walk or letting {@link DepthSink} signal stop. Left as it is until there is a
   * benchmark saying which.
   */
  @Override
  public long fillableQty(final Order aggressor, final BookSide opposing) {
    if (opposing.isEmpty()) return 0L;

    probe.reset(aggressor);
    opposing.depth(Integer.MAX_VALUE, probe);
    return probe.fillable();
  }

  private static boolean crosses(final Order aggressor, final long restingPrice) {
    return aggressor.side() == OrderSide.BUY
        ? aggressor.price() >= restingPrice
        : aggressor.price() <= restingPrice;
  }

  /** Accumulates crossing liquidity for one probe, then is reset and used again. */
  private static final class FillableProbe implements DepthSink {
    private OrderSide side;
    private long limitPrice;
    private long wanted;
    private long found;

    private void reset(final Order aggressor) {
      this.side = aggressor.side();
      this.limitPrice = aggressor.price();
      this.wanted = aggressor.remainingQty();
      this.found = 0L;
    }

    @Override
    public void onLevel(final long price, final long qty) {
      if (found >= wanted) return;
      final boolean crossing = side == OrderSide.BUY ? limitPrice >= price : limitPrice <= price;
      if (!crossing) return;
      found += qty;
    }

    private long fillable() {
      return found < wanted ? found : wanted;
    }
  }
}

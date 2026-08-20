package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.dto.TopOfBook;
import com.imc.me.event.result.AmendOutcome;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.Cancelled;
import com.imc.me.event.result.NotFound;
import com.imc.me.event.result.SubmitOutcome;
import com.imc.me.event.sink.CollectingDepthSink;
import com.imc.me.matching.TradeSink;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The obvious implementation: one list of resting orders, scanned.
 *
 * <p>This is the bottom of the ladder, and it has two jobs. It is the baseline every later
 * implementation is measured against, so it has to be the honest naive thing rather than a
 * half-optimised one. And it is the oracle the fast implementations are diffed against, so it has
 * to be simple enough that when it disagrees with {@link TreeMapOrderBook}, the fast one is wrong.
 *
 * <p>Everything is linear in the number of resting orders. There are no price levels, no id index
 * and no use of the intrusive links on the order entity. Finding the best crossing order is a full
 * scan, and so is a cancel.
 *
 * <p>It deliberately does not use {@link com.imc.me.matching.Matcher}. Sharing a matcher with the
 * implementation under test would hide a bug in the matcher from the diff, which is most of the
 * value of having an oracle. The gate and remainder rules are therefore restated here rather than
 * reused, and the two implementations agreeing is then worth something.
 *
 * <p>Order mutation happens here rather than through a price level, because with no level there is
 * nothing else that owns the invariant (OOD-1). The pair of fills is still applied in one place.
 */
public final class NaiveOrderBook implements OrderBook {

  private final List<Order> resting = new ArrayList<>();

  @Override
  public SubmitOutcome submit(final Order order, final TradeSink sink) {
    final SubmitOutcome gated = gate(order);
    if (gated != null) return gated;

    while (order.remainingQty() > 0) {
      final Order best = bestCrossing(order);
      if (best == null) break;

      final long qty =
          order.remainingQty() < best.remainingQty() ? order.remainingQty() : best.remainingQty();

      best.applyFill(qty);
      order.applyFill(qty);
      sink.onTrade(order.orderId(), best.orderId(), best.price(), qty);

      if (best.remainingQty() == 0) resting.remove(best);
    }

    if (order.remainingQty() == 0) return SubmitOutcome.FILLED;

    return switch (order.type()) {
      case LIMIT, POST -> {
        resting.add(order);
        yield SubmitOutcome.RESTED;
      }
      case MARKET, IOC -> SubmitOutcome.REMAINDER_CANCELLED;
      case FOK ->
          throw new IllegalStateException(
              "FOK order " + order.orderId() + " has a remainder; the gate and the walk disagree");
    };
  }

  @Override
  public AmendOutcome amend(
      final long orderId, final long newQty, final long newPrice, final TradeSink sink) {
    final Order order = find(orderId);
    if (order == null) return AmendOutcome.NOT_FOUND;

    final long remaining = order.remainingQty();
    if (newPrice == order.price() && newQty < remaining) {
      order.reduceQty(remaining - newQty);
      return AmendOutcome.REDUCED_KEPT_PRIORITY;
    }

    final Order replacement = Order.of(orderId, newPrice, newQty, order.side(), order.type());

    // Gated before the original is unlinked, so a refused amend leaves it resting (API-8.2).
    if (gate(replacement) != null) return AmendOutcome.REJECTED_WOULD_CROSS;

    resting.remove(order);

    return switch (submit(replacement, sink)) {
      case RESTED -> AmendOutcome.REQUEUED_LOST_PRIORITY;
      case FILLED -> AmendOutcome.FILLED_ON_AMEND;
      case REMAINDER_CANCELLED -> AmendOutcome.REMAINDER_CANCELLED_ON_AMEND;
      case KILLED, REJECTED_WOULD_CROSS ->
          throw new IllegalStateException(
              "amend "
                  + orderId
                  + " passed the gate then failed it; the gate is not deterministic");
    };
  }

  @Override
  public CancelResult cancel(final long orderId) {
    final Order order = find(orderId);
    if (order == null) return new NotFound(orderId);

    resting.remove(order);
    return new Cancelled(orderId);
  }

  @Override
  public TopOfBook topOfBook(final OrderSide side) {
    long best = 0L;
    boolean found = false;
    for (final Order candidate : resting) {
      if (candidate.side() != side) continue;
      if (!found || betterOnSide(side, candidate.price(), best)) {
        best = candidate.price();
        found = true;
      }
    }
    if (!found) return TopOfBook.empty(side);

    return TopOfBook.of(side, best, qtyAt(side, best));
  }

  @Override
  public Depth depth(final OrderSide side, final int maxLevels) {
    final List<Long> prices = new ArrayList<>();
    for (final Order candidate : resting) {
      if (candidate.side() == side && !prices.contains(candidate.price())) {
        prices.add(candidate.price());
      }
    }
    prices.sort(side == OrderSide.BUY ? Comparator.reverseOrder() : Comparator.naturalOrder());

    final CollectingDepthSink collector = new CollectingDepthSink(maxLevels);
    int emitted = 0;
    for (final long price : prices) {
      if (emitted++ == maxLevels) break;
      if (!collector.onLevel(price, qtyAt(side, price))) break;
    }
    return new Depth(side, collector.levels());
  }

  private SubmitOutcome gate(final Order order) {
    return switch (order.type()) {
      case FOK -> fillableQty(order) < order.remainingQty() ? SubmitOutcome.KILLED : null;
      case POST -> fillableQty(order) > 0 ? SubmitOutcome.REJECTED_WOULD_CROSS : null;
      case LIMIT, MARKET, IOC -> null;
    };
  }

  private long fillableQty(final Order aggressor) {
    long found = 0L;
    for (final Order candidate : resting) {
      if (crosses(aggressor, candidate)) found += candidate.remainingQty();
    }
    return found < aggressor.remainingQty() ? found : aggressor.remainingQty();
  }

  /**
   * The best crossing order, or {@code null} if none crosses.
   *
   * <p>Improvement is strict, so an equal price keeps the order found earlier. Arrival order is
   * list order, which is how FIFO within a price falls out without a queue (FR-3.2).
   */
  private Order bestCrossing(final Order aggressor) {
    Order best = null;
    for (final Order candidate : resting) {
      if (!crosses(aggressor, candidate)) continue;
      if (best == null || betterOnSide(candidate.side(), candidate.price(), best.price())) {
        best = candidate;
      }
    }
    return best;
  }

  private static boolean crosses(final Order aggressor, final Order candidate) {
    if (candidate.side() == aggressor.side()) return false;
    return aggressor.side() == OrderSide.BUY
        ? aggressor.price() >= candidate.price()
        : aggressor.price() <= candidate.price();
  }

  /** Whether {@code candidate} is a better price than {@code best} for an order resting on side. */
  private static boolean betterOnSide(final OrderSide side, final long candidate, final long best) {
    return side == OrderSide.BUY ? candidate > best : candidate < best;
  }

  private long qtyAt(final OrderSide side, final long price) {
    long qty = 0L;
    for (final Order candidate : resting) {
      if (candidate.side() == side && candidate.price() == price) qty += candidate.remainingQty();
    }
    return qty;
  }

  private Order find(final long orderId) {
    for (final Order candidate : resting) {
      if (candidate.orderId() == orderId) return candidate;
    }
    return null;
  }
}

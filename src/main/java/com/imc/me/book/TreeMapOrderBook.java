package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.dto.TopOfBook;
import com.imc.me.event.result.AmendOutcome;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.Cancelled;
import com.imc.me.event.result.NotFound;
import com.imc.me.event.result.SubmitOutcome;
import com.imc.me.event.sink.CollectingDepthSink;
import com.imc.me.event.sink.TradeEventSink;
import com.imc.me.matching.Matcher;
import com.imc.me.matching.TradeSink;
import com.imc.me.sequencer.Sequencer;

/**
 * The single writer. Owns both sides, each with its own id index, and drives the matcher over the
 * opposing side.
 *
 * <p>The engine validates and sequences a command and then calls in here. Keeping the matcher
 * behind this boundary, rather than having the engine coordinate a book and a matcher itself, means
 * every future book implementation inherits the choreography instead of reimplementing it.
 */
public final class TreeMapOrderBook implements OrderBook {
  private final BookSide bids = new TreeMapBookSide(OrderSide.BUY);
  private final BookSide asks = new TreeMapBookSide(OrderSide.SELL);
  private final Matcher matcher;
  private final Sequencer sequencer;

  /**
   * The one stamping sink, reused for every command so the write path stays inside its allocation
   * budget (OOD-11). Safe because there is one writer (OOD-2) and the sink's useful lifetime is a
   * single synchronous call.
   */
  private final SequencingTradeSink stamper = new SequencingTradeSink();

  public TreeMapOrderBook(final Matcher matcher) {
    this(matcher, new Sequencer());
  }

  /** Takes an existing sequencer so a whole engine shares one total order (OOD-13). */
  public TreeMapOrderBook(final Matcher matcher, final Sequencer sequencer) {
    this.matcher = matcher;
    this.sequencer = sequencer;
  }

  /**
   * Turns the matcher's executions into sequenced events.
   *
   * <p>This is the seam that keeps the matching algorithm ignorant of sequencing. The matcher
   * reports that two orders executed and the book decides where that sits in the total order.
   * Handing the matcher a sequencer would give the algorithm a job with nothing to do with
   * matching, and would let two matchers disagree about numbering.
   */
  private final class SequencingTradeSink implements TradeSink {
    private TradeEventSink target;

    @Override
    public void onTrade(
        final long aggressorId, final long restingId, final long price, final long qty) {
      target.onTrade(sequencer.next(), aggressorId, restingId, price, qty);
    }
  }

  /** Points the stamper at this command's consumer and hands it to the matcher. */
  private TradeSink stampingInto(final TradeEventSink sink) {
    stamper.target = sink;
    return stamper;
  }

  /**
   * Submission in three phases: gate, walk, remainder.
   *
   * <p>Only the first and last know about order type, since the walk is identical for all five,
   * which is why there is one matcher rather than one per type (OOD-8).
   *
   * <p>Neither switch carries a {@code default} arm, so adding a constant to {@link OrderType}
   * fails compilation here at every point that has to decide something about it.
   */
  @Override
  public SubmitOutcome submit(final Order order, final TradeEventSink sink) {
    final BookSide opposing = opposingSide(order.side());

    // PHASE 1: GATE. Type-dependent, pre-trade, and the book is untouched if it fires.
    final SubmitOutcome gated = gate(order, opposing);
    if (gated != null) return gated;

    // PHASE 2: WALK. Type-agnostic, and the hot path.
    matcher.match(order, opposing, stampingInto(sink));

    // PHASE 3: REMAINDER. Type-dependent, post-trade.
    if (order.remainingQty() == 0) return SubmitOutcome.FILLED;

    return switch (order.type()) {
      // A limit order rests at its own price (FR-2.1). Post-only rests too, since it got here by
      // passing the gate, which proved it does not cross.
      case LIMIT, POST -> {
        sideFor(order.side()).addOrder(order);
        yield SubmitOutcome.RESTED;
      }
      // A market order carries a price sentinel, so resting it would put a meaningless price in the
      // book. Its remainder is cancelled (FR-2.2, FR-2.3), and IOC is the same rule asked for
      // explicitly by the client (FR-2.4).
      case MARKET, IOC -> SubmitOutcome.REMAINDER_CANCELLED;
      // Unreachable: the gate returned KILLED unless the whole quantity was fillable, and the walk
      // fills everything it said it would. Left as an exception so that a matcher whose probe and
      // walk disagree fails loudly here instead of silently resting or dropping an FOK order.
      case FOK ->
          throw new IllegalStateException(
              "FOK order " + order.orderId() + " has a remainder; probe and walk disagree");
    };
  }

  /**
   * Amends a resting order to a new quantity and price, carrying the client's full intent.
   *
   * <p>The client sends the complete new state rather than a delta, which is how FIX models it: an
   * {@code OrderCancelReplaceRequest} carries the whole replacement order.
   *
   * <p>Two paths, and the priority rule is why they differ (FR-4.4, FR-4.5). A quantity decrease at
   * the same price is reduced in place and keeps priority, which is safe because reducing takes
   * nothing from anybody else and every order queued behind is strictly better off. Anything else,
   * an increase or any price change, is unlinked and treated as a fresh arrival and loses priority.
   * Without that, a client could hold a good queue position with a token order and inflate it on
   * seeing flow, which is the abuse price-time priority exists to prevent.
   *
   * <p>The second path is remove-then-submit, which is literally cancel and replace, so a reprice
   * gets the three things a hand-rolled version tends to miss: priority loss, execution if the new
   * price crosses, and the order's own remainder policy. It is also why {@code Order.price} is
   * final, since an order's price is what identifies the level holding it (OOD-14).
   */
  @Override
  public AmendOutcome amend(
      final long orderId, final long newQty, final long newPrice, final TradeEventSink sink) {
    final BookSide side = sideHolding(orderId);
    if (side == null) return AmendOutcome.NOT_FOUND;

    final Order order = side.get(orderId);
    final long remaining = order.remainingQty();

    if (newPrice == order.price() && newQty < remaining) {
      side.reduce(order, remaining - newQty);
      return AmendOutcome.REDUCED_KEPT_PRIORITY;
    }

    final Order replacement = Order.of(orderId, newPrice, newQty, order.side(), order.type());

    // Gate the replacement before unlinking the original, so a refused amend leaves the original
    // resting exactly as it was (API-8.2). Removing first and then finding the gate refuses would
    // silently cancel an order the client asked to keep.
    //
    // Only reachable for POST, since a resting order can only be LIMIT or POST. Reusing gate() here
    // rather than re-testing the condition keeps one crossing check instead of two that can drift.
    final SubmitOutcome gated = gate(replacement, opposingSide(order.side()));
    if (gated != null) return AmendOutcome.REJECTED_WOULD_CROSS;

    side.remove(order);

    return switch (submit(replacement, sink)) {
      case RESTED -> AmendOutcome.REQUEUED_LOST_PRIORITY;
      case FILLED -> AmendOutcome.FILLED_ON_AMEND;
      case REMAINDER_CANCELLED -> AmendOutcome.REMAINDER_CANCELLED_ON_AMEND;
      // Unreachable: the gate above already ran on this order against this side, so a refusal here
      // would mean the probe is not a pure function of (order, side).
      case KILLED, REJECTED_WOULD_CROSS ->
          throw new IllegalStateException(
              "amend " + orderId + " passed the gate then failed it; probe is not deterministic");
    };
  }

  /**
   * Phase 1 for any order: the pre-trade, type-dependent constraint. Returns the outcome that
   * refuses the order, or {@code null} if it may go on to the walk.
   *
   * <p>Shared by {@code submit} and {@code amend} so there is one crossing check rather than two
   * that can drift apart. Null-as-pass rather than an {@code Optional} because this is the hot path
   * and an {@code Optional} would allocate per order (OOD-11).
   */
  private SubmitOutcome gate(final Order order, final BookSide opposing) {
    return switch (order.type()) {
      // FOK cannot be a remainder policy: by the time the remainder is known the executions have
      // happened and there is no un-trading. So fillability is decided before the walk, from the
      // same crossing logic the walk uses (FR-2.5).
      case FOK ->
          matcher.fillableQty(order, opposing) < order.remainingQty() ? SubmitOutcome.KILLED : null;
      // Post-only must never take liquidity, so the question is whether it would cross at all, and
      // it is the same probe (FR-2.6).
      case POST ->
          matcher.fillableQty(order, opposing) > 0 ? SubmitOutcome.REJECTED_WOULD_CROSS : null;
      // No pre-trade constraint: free to take whatever is available.
      case LIMIT, MARKET, IOC -> null;
    };
  }

  @Override
  public CancelResult cancel(final long orderId) {
    final BookSide side = sideHolding(orderId);
    if (side == null) return new NotFound(orderId);

    side.remove(side.get(orderId));
    // TODO: per-order fill history isn't tracked yet, so fills-before-cancellation is empty.
    return Cancelled.unfilled(orderId);
  }

  /**
   * The side currently resting this order, or {@code null} if neither is.
   *
   * <p>Two lookups because each side owns its own id index, rather than a book-level map that
   * could disagree with the sides (OOD-14). Costs one failed hash lookup on the cancel path.
   */
  private BookSide sideHolding(final long orderId) {
    if (bids.get(orderId) != null) return bids;
    if (asks.get(orderId) != null) return asks;
    return null;
  }

  @Override
  public TopOfBook topOfBook(final OrderSide side) {
    final BookSide book = sideFor(side);
    if (book.isEmpty()) return TopOfBook.empty(side);
    final PriceLevel best = book.bestLevel();
    return TopOfBook.of(side, best.price(), best.totalQty());
  }

  /**
   * Materialises a depth snapshot for a client, which is the edge paying for its own convenience
   * (OOD-3). A publishing consumer implements {@link DepthSink} directly and allocates nothing.
   */
  @Override
  public Depth depth(final OrderSide side, final int maxLevels) {
    final CollectingDepthSink collector = new CollectingDepthSink(maxLevels);
    sideFor(side).depth(maxLevels, collector);
    return new Depth(side, collector.levels());
  }

  /**
   * Package-private rather than private so a same-package test can assert on the FIFO structure
   * inside a level, which is the only way to verify time priority (FR-3.2, FR-4.4, FR-4.5). The
   * public API exposes aggregates, and an aggregate cannot distinguish queue order.
   */
  BookSide sideFor(final OrderSide side) {
    return (side == OrderSide.BUY) ? bids : asks;
  }

  private BookSide opposingSide(final OrderSide side) {
    return (side == OrderSide.BUY) ? asks : bids;
  }
}

package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.dto.OrderStatus;
import com.imc.me.event.dto.TopOfBook;
import com.imc.me.event.result.AmendResult;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.Cancelled;
import com.imc.me.event.result.NotFound;
import com.imc.me.event.result.SubmitOutcome;
import com.imc.me.event.sink.CollectingDepthSink;
import com.imc.me.matching.Matcher;
import com.imc.me.matching.TradeSink;

/**
 * The single writer. Owns both sides (each with its own id index) and holds the Matcher as a
 * strategy it drives over the opposing side. The MatchingEngine validates/sequences commands and
 * calls in here; it never touches matching mechanics keeping the Matcher inside this boundary,
 * rather than having the engine coordinate the book and the matcher, is the whole point.
 */
public final class TreeMapOrderBook implements OrderBook {
  private final BookSide bids = new TreeMapBookSide(OrderSide.BUY);
  private final BookSide asks = new TreeMapBookSide(OrderSide.SELL);
  private final Matcher matcher;

  public TreeMapOrderBook(final Matcher matcher) {
    this.matcher = matcher;
  }

  /**
   * Submission in three phases: <b>gate</b>, <b>walk</b>, <b>remainder</b>.
   *
   * <p>Only the first and last know about order type; the walk is identical for every type (OOD-8).
   * That is the whole reason there is one {@code Matcher} rather than one per type — five
   * implementations behind {@link Matcher} would put a megamorphic call site in the hottest loop in
   * the system and stop the JIT inlining through it, to vary 10% of the behaviour while duplicating
   * the other 90%.
   *
   * <p>Neither switch has a {@code default} arm, deliberately: adding a constant to {@link OrderType}
   * then fails compilation here, at every point that has to decide something about it. That is how
   * you find all of them, and it is the payoff for making variation data instead of subtypes.
   */
  @Override
  public SubmitOutcome submit(final Order order, final TradeSink sink) {
    final BookSide opposing = opposingSide(order.side());

    // --- PHASE 1: GATE. Type-dependent, pre-trade, and the book is untouched if it fires. ---
    switch (order.type()) {
      case FOK -> {
        // FOK cannot be expressed as a remainder policy: by the time the remainder is known, the
        // executions have already happened and there is no un-trading. So fillability has to be
        // decided before the walk, from the same crossing logic the walk uses (FR-2.5).
        if (matcher.fillableQty(order, opposing) < order.remainingQty()) return SubmitOutcome.KILLED;
      }
      case POST -> {
        // Post-only must never take liquidity, so "would this cross at all" is the question, and it
        // is the same probe (FR-2.6).
        if (matcher.fillableQty(order, opposing) > 0) return SubmitOutcome.REJECTED_WOULD_CROSS;
      }
      case LIMIT, MARKET, IOC -> {
        // No pre-trade constraint: these are free to take whatever is available.
      }
    }

    // --- PHASE 2: WALK. Type-agnostic. The hot path. ---
    matcher.match(order, opposing, sink);

    // --- PHASE 3: REMAINDER. Type-dependent, post-trade. ---
    if (order.remainingQty() == 0) return SubmitOutcome.FILLED;

    return switch (order.type()) {
      // A limit order rests at its own price (FR-2.1). A post-only order rests too -- it reached
      // here only by passing the gate, which proved it does not cross.
      case LIMIT, POST -> {
        sideFor(order.side()).addOrder(order);
        yield SubmitOutcome.RESTED;
      }
      // A market order must never rest (FR-2.2) -- it carries a price sentinel, so resting it would
      // put a meaningless price in the book. Its remainder is cancelled, which is the stated and
      // consistently enforced policy (FR-2.3). IOC is the same rule made explicit by the client
      // (FR-2.4).
      case MARKET, IOC -> SubmitOutcome.REMAINDER_CANCELLED;
      // Unreachable by construction: the gate returned KILLED unless the whole quantity was
      // fillable, and the walk fills everything it said it would. Stated as an exception rather
      // than folded into another arm so that a matcher whose probe and walk disagree fails loudly
      // here instead of silently resting or dropping an FOK order.
      case FOK ->
          throw new IllegalStateException(
              "FOK order " + order.orderId() + " has a remainder; probe and walk disagree");
    };
  }

  @Override
  public AmendResult amend(final long orderId) {
    // TODO(FR-4.3/4.4/4.5): qty-decrease reduces in place and keeps priority; increase/reprice
    // unlinks and re-appends (loses priority).
    throw new UnsupportedOperationException("amend not implemented yet");
  }

  @Override
  public CancelResult cancel(final long orderId) {
    Order order = bids.get(orderId);
    BookSide side = bids;
    if (order == null) {
      order = asks.get(orderId);
      side = asks;
    }
    if (order == null) return new NotFound(orderId);

    side.remove(order);
    // TODO: per-order fill history isn't tracked yet, so fills-before-cancellation is empty.
    return Cancelled.unfilled(orderId);
  }

  @Override
  public TopOfBook topOfBook(final OrderSide side) {
    final BookSide book = sideFor(side);
    if (book.isEmpty()) return TopOfBook.empty(side);
    final PriceLevel best = book.bestLevel();
    return TopOfBook.of(side, best.price(), best.totalQty());
  }

  /**
   * Materialises a depth snapshot for a client.
   *
   * <p>This is the boundary paying for its own convenience (OOD-3): the side emits primitives, and
   * the collecting sink builds the immutable {@link Depth} because the caller is a query consumer
   * that is about to serialise or assert on it. A publishing consumer skips this and implements
   * {@link DepthSink} directly, allocating nothing.
   */
  @Override
  public Depth depth(final OrderSide side, final int maxLevels) {
    final CollectingDepthSink collector = new CollectingDepthSink(maxLevels);
    sideFor(side).depth(maxLevels, collector);
    return new Depth(side, collector.levels());
  }

  @Override
  public OrderStatus orderStatus(final long orderId) {
    // TODO(FR-5.4): filled/cancelled orders leave the resting set, so status isn't answerable
    // from bids/asks alone — this needs an order registry that outlives the book.
    throw new UnsupportedOperationException("orderStatus not implemented yet");
  }

  private BookSide sideFor(final OrderSide side) {
    return (side == OrderSide.BUY) ? bids : asks;
  }

  private BookSide opposingSide(final OrderSide side) {
    return (side == OrderSide.BUY) ? asks : bids;
  }
}

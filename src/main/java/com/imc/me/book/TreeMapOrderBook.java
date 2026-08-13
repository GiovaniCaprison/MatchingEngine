package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.dto.OrderStatus;
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
 * The single writer. Owns both sides (each with its own id index) and holds the Matcher as a
 * strategy it drives over the opposing side. The MatchingEngine validates/sequences commands and
 * calls in here; it never touches matching mechanics keeping the Matcher inside this boundary,
 * rather than having the engine coordinate the book and the matcher, is the whole point.
 */
public final class TreeMapOrderBook implements OrderBook {
  private final BookSide bids = new TreeMapBookSide(OrderSide.BUY);
  private final BookSide asks = new TreeMapBookSide(OrderSide.SELL);
  private final Matcher matcher;
  private final Sequencer sequencer;

  /**
   * The one stamping sink, reused for every command.
   *
   * <p>Reused rather than allocated per submit so the write path stays inside its allocation budget
   * (OOD-11). Safe because there is exactly one writer (OOD-2) and its lifetime is a single
   * synchronous call — the retarget happens, the matcher walks, the walk returns.
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
   * Turns the matcher's executions into the engine's sequenced events.
   *
   * <p>This is the seam that keeps the matching algorithm ignorant of sequencing. The matcher reports
   * "these two orders executed"; the book decides where that sits in the total order. Handing the
   * matcher a sequencer instead would give the algorithm a responsibility with nothing to do with
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
  public SubmitOutcome submit(final Order order, final TradeEventSink sink) {
    final BookSide opposing = opposingSide(order.side());

    // --- PHASE 1: GATE. Type-dependent, pre-trade, and the book is untouched if it fires. ---
    final SubmitOutcome gated = gate(order, opposing);
    if (gated != null) return gated;

    // --- PHASE 2: WALK. Type-agnostic. The hot path. ---
    matcher.match(order, opposing, stampingInto(sink));

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

  /**
   * Amends a resting order to a new quantity and price, carrying the client's full intent.
   *
   * <p>The client sends the complete new state rather than a delta, which is how FIX models it
   * ({@code OrderCancelReplaceRequest} carries the whole replacement order). A delta would make
   * "unchanged" ambiguous with "set to zero" and would need a sentinel per field.
   *
   * <p><b>Two paths, and the priority rule is the reason they differ</b> (FR-4.4, FR-4.5):
   *
   * <ul>
   *   <li><b>Quantity decrease at the same price</b> — reduced in place, priority kept. Safe because
   *       reducing takes nothing from anyone else: every order queued behind this one is strictly
   *       better off, so there is no fairness argument for re-queueing.
   *   <li><b>Anything else</b> — increase, or any price change — unlinked and treated as a fresh
   *       arrival, so priority is lost. Otherwise a client could hold a good queue position with a
   *       token order and inflate it on seeing flow, which is the abuse price-time priority exists to
   *       prevent.
   * </ul>
   *
   * <p>The second path is implemented as remove-then-submit, which is not a shortcut but the correct
   * model — it is literally cancel/replace. It also means a reprice automatically gets the three
   * things a hand-rolled reprice tends to get wrong: priority loss, execution if the new price
   * crosses, and the order's own type-appropriate remainder policy (OOD-8).
   *
   * <p>It is also why {@code Order.price} stays {@code final}. Mutating the price in place would
   * break the invariant that an order's price identifies the level holding it — which {@code
   * TreeMapBookSide.remove} depends on to find that level (OOD-14).
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

    // Gate the replacement BEFORE unlinking the original, so that a refused amend leaves the
    // original resting exactly as it was (API-8.2). Doing it the other way round -- remove, then
    // discover the gate refuses -- would silently cancel an order the client asked to keep, which is
    // the worst possible interpretation of "your amend was rejected".
    //
    // Only reachable for POST, since a resting order can only be LIMIT or POST: MARKET, IOC and FOK
    // never rest. Reusing gate() rather than re-testing the condition here is deliberate -- two
    // copies of a crossing check that can drift apart is exactly the bug the shared probe avoids.
    final SubmitOutcome gated = gate(replacement, opposingSide(order.side()));
    if (gated != null) return AmendOutcome.REJECTED_WOULD_CROSS;

    side.remove(order);

    return switch (submit(replacement, sink)) {
      case RESTED -> AmendOutcome.REQUEUED_LOST_PRIORITY;
      case FILLED -> AmendOutcome.FILLED_ON_AMEND;
      case REMAINDER_CANCELLED -> AmendOutcome.REMAINDER_CANCELLED_ON_AMEND;
      // Unreachable: the gate above already ran on this exact order against this exact side, so a
      // refusal here would mean the probe is not a pure function of (order, side).
      case KILLED, REJECTED_WOULD_CROSS ->
          throw new IllegalStateException(
              "amend " + orderId + " passed the gate then failed it; probe is not deterministic");
    };
  }

  /**
   * Phase 1 for any order: the pre-trade, type-dependent constraint. Returns the outcome that
   * refuses the order, or {@code null} if it may proceed to the walk.
   *
   * <p>Shared by {@code submit} and {@code amend} so there is one crossing check rather than two that
   * can drift apart. {@code null}-as-pass rather than an {@code Optional} because this is the hot path
   * and an {@code Optional} would allocate per order (OOD-11).
   */
  private SubmitOutcome gate(final Order order, final BookSide opposing) {
    return switch (order.type()) {
      // FOK cannot be expressed as a remainder policy: by the time the remainder is known the
      // executions have already happened and there is no un-trading. So fillability is decided
      // before the walk, from the same crossing logic the walk uses (FR-2.5).
      case FOK ->
          matcher.fillableQty(order, opposing) < order.remainingQty() ? SubmitOutcome.KILLED : null;
      // Post-only must never take liquidity, so "would this cross at all" is the question, and it is
      // the same probe (FR-2.6).
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
   * <p>Two lookups because each side owns its own id index — which is what keeps an order's
   * membership managed entirely by the side holding it (OOD-1/OOD-14) rather than by a book-level map
   * that could disagree with the sides. The cost is one extra failed hash lookup on the cancel path;
   * the alternative costs a class of state-divergence bug.
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

  /**
   * Package-private rather than private so that same-package tests can assert on the FIFO structure
   * inside a level — which is the only way to verify time priority (FR-3.2, FR-4.4/4.5), since the
   * public API exposes aggregates and an aggregate cannot distinguish queue order.
   */
  BookSide sideFor(final OrderSide side) {
    return (side == OrderSide.BUY) ? bids : asks;
  }

  private BookSide opposingSide(final OrderSide side) {
    return (side == OrderSide.BUY) ? asks : bids;
  }
}

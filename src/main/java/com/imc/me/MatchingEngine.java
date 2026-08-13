package com.imc.me;

import com.imc.me.book.Order;
import com.imc.me.book.OrderBook;
import com.imc.me.book.TreeMapOrderBook;
import com.imc.me.domain.Instrument;
import com.imc.me.domain.OrderSide;
import com.imc.me.domain.OrderType;
import com.imc.me.event.command.NewOrder;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.dto.OrderStatus;
import com.imc.me.event.dto.Status;
import com.imc.me.event.dto.TopOfBook;
import com.imc.me.event.result.Accepted;
import com.imc.me.event.result.AmendOutcome;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.Cancelled;
import com.imc.me.event.result.RejectReason;
import com.imc.me.event.result.Rejected;
import com.imc.me.event.result.SubmitOutcome;
import com.imc.me.event.result.SubmitResult;
import com.imc.me.event.sink.CollectingTradeSink;
import com.imc.me.event.sink.TradeEventSink;
import com.imc.me.event.sink.EngineListener;
import com.imc.me.matching.Matcher;
import com.imc.me.matching.PriceTimeMatcher;
import com.imc.me.registry.OrderRegistry;
import com.imc.me.sequencer.Sequencer;
import com.imc.me.util.Prices;
import com.imc.me.validation.OrderValidator;
import java.util.Optional;

/**
 * The engine's public API and its validation boundary.
 *
 * <p>Everything a client can do goes through here, and this class is the only place that:
 *
 * <ol>
 *   <li><b>validates</b> — so nothing below it ever re-checks an argument (OOD-5);
 *   <li><b>mints identity</b> — one sequencer, one total order (OOD-13);
 *   <li><b>records lifecycle</b> — the registry, which outlives the book (OOD-14);
 *   <li><b>materialises DTOs</b> — the edge paying for its own convenience (OOD-3).
 * </ol>
 *
 * <p>The order of the first two matters and is not the obvious one: <b>the uid is minted before
 * validation.</b> That spends an id on a rejected order, which is free, and buys something valuable —
 * every order the engine has ever seen is addressable and appears in the registry, so a client cannot
 * mistake a rejection for a lost message.
 *
 * <p>It does <i>not</i> coordinate matching. It hands a validated order to the book, and the book drives
 * the matcher over the opposing side. An engine that orchestrated book-and-matcher itself would put
 * matching mechanics in the boundary layer, and every future book implementation would have to
 * reimplement the choreography.
 *
 * <p><b>Single-writer and not thread-safe by design</b> (OOD-2). One thread calls these methods.
 * Concurrency comes from partitioning instruments across engines, never from sharing one.
 */
public final class MatchingEngine {

  private final Instrument instrument;
  private final OrderBook book;
  private final Sequencer sequencer;
  private final OrderRegistry registry = new OrderRegistry();

  /**
   * Listeners as a plain array rather than a {@code List} so that publishing iterates without an
   * iterator allocation per event (OOD-11). Registration is a startup-time operation, so copying the
   * array on register is the right trade: cheap where it is rare, free where it is hot.
   */
  private EngineListener[] listeners = new EngineListener[0];

  public MatchingEngine(final Instrument instrument) {
    this(instrument, new PriceTimeMatcher());
  }

  /**
   * Takes the matching strategy rather than hardwiring it.
   *
   * <p>This is the substitution that justifies {@link Matcher} existing at all (OOD-17): price-time
   * versus pro-rata is a real venue-level variation, and the reference {@code TreeMapOrderBook} will
   * eventually be differential-tested against a faster book using the same matcher. It also means the
   * boundary's own behaviour -- validation, identity, the registry, event fan-out -- is testable
   * without a working walk, which is the difference between being able to build this incrementally and
   * having to finish everything before anything can be verified.
   */
  public MatchingEngine(final Instrument instrument, final Matcher matcher) {
    this.instrument = instrument;
    this.sequencer = new Sequencer();
    this.book = new TreeMapOrderBook(matcher, sequencer);
  }

  /** Registers a consumer of the outbound event stream (API-7.1). */
  public void register(final EngineListener listener) {
    final EngineListener[] grown = new EngineListener[listeners.length + 1];
    System.arraycopy(listeners, 0, grown, 0, listeners.length);
    grown[listeners.length] = listener;
    listeners = grown;
  }

  /**
   * Places an order (FR-1.1, FR-1.2, FR-1.3).
   *
   * <p>Validation happens strictly before any state is touched, so a rejection leaves the book
   * bit-identical (API-8.2) — not by cleaning up afterwards, but because nothing was done yet.
   */
  public SubmitResult submit(final NewOrder command) {
    final long orderId = sequencer.next();

    final RejectReason invalid = OrderValidator.validate(command, instrument);
    if (invalid != null) {
      // Registered even though it was refused, so "I sent that, what happened?" is answerable for
      // every order a client ever sent, not only the ones that made it into the book.
      // Clamped because the rejected quantity may be the very thing that was invalid, and an order
      // entity with a negative quantity would report a nonsensical remainder to a status query.
      final long registeredQty = command.qty() > 0 ? command.qty() : 0L;
      registry.rejected(
          Order.of(orderId, command.price(), registeredQty, sideOf(command), typeOf(command)));
      return reject(command.clientOrderId(), orderId, invalid);
    }

    // A MARKET order's price is replaced by a sentinel here, at the boundary, after the client's own
    // price has been checked -- which is why a sentinel can safely take a value no client could send.
    final long price =
        command.type() == OrderType.MARKET
            ? Prices.marketPrice(command.side())
            : command.price();

    final Order order = Order.of(orderId, price, command.qty(), command.side(), command.type());
    registry.accepted(order);

    final CollectingTradeSink fills = new CollectingTradeSink();
    final SubmitOutcome outcome = book.submit(order, fanOutTo(fills));

    return switch (outcome) {
      case KILLED -> reject(command.clientOrderId(), orderId, RejectReason.FOK_UNFILLABLE);
      case REJECTED_WOULD_CROSS -> reject(command.clientOrderId(), orderId, RejectReason.WOULD_CROSS);
      case FILLED, RESTED, REMAINDER_CANCELLED -> {
        if (outcome == SubmitOutcome.REMAINDER_CANCELLED) registry.cancelled(orderId);
        notifyAccepted(command.clientOrderId(), orderId);
        if (outcome != SubmitOutcome.RESTED) {
          notifyTerminal(orderId, registry.statusOf(orderId).status());
        }
        yield new Accepted(command.clientOrderId(), orderId, outcome, fills.fills());
      }
    };
  }

  /** Cancels a resting order by uid (FR-4.1). Idempotent: a second cancel is not-found (FR-4.2). */
  public CancelResult cancel(final long orderId) {
    final CancelResult result = book.cancel(orderId);
    if (result instanceof Cancelled) {
      registry.cancelled(orderId);
      notifyTerminal(orderId, Status.CANCELLED);
    }
    return result;
  }

  /** Amends a resting order (FR-4.3). See {@link AmendOutcome} for what happens to queue priority. */
  public AmendOutcome amend(final long orderId, final long newQty, final long newPrice) {
    if (newQty <= 0) return AmendOutcome.NOT_FOUND;

    final CollectingTradeSink fills = new CollectingTradeSink();
    final AmendOutcome outcome = book.amend(orderId, newQty, newPrice, fanOutTo(fills));

    if (outcome == AmendOutcome.FILLED_ON_AMEND
        || outcome == AmendOutcome.REMAINDER_CANCELLED_ON_AMEND) {
      if (outcome == AmendOutcome.REMAINDER_CANCELLED_ON_AMEND) registry.cancelled(orderId);
      notifyTerminal(orderId, registry.statusOf(orderId).status());
    }
    return outcome;
  }

  /** Best price and aggregate quantity on one side (FR-5.1); empty if the side has none (FR-5.2). */
  public TopOfBook topOfBook(final OrderSide side) {
    return book.topOfBook(side);
  }

  /** Aggregated levels, best price first, capped by the caller (FR-5.3, OOD-10). */
  public Depth depth(final OrderSide side, final int maxLevels) {
    return book.depth(side, maxLevels);
  }

  /**
   * An order's current state, including remaining quantity (FR-5.4).
   *
   * <p>{@code Optional} rather than a nullable return, because this is an edge query rather than the
   * hot path, and it keeps a null out of the public API (OOD-6). A sealed found/not-found pair would be
   * the more consistent answer, but Java requires a sealed hierarchy's members to share a package
   * without modules, and these two types belong in different ones — so this is the honest compromise
   * rather than a pretend one.
   */
  public Optional<OrderStatus> status(final long orderId) {
    return Optional.ofNullable(registry.statusOf(orderId));
  }

  /** The instrument this engine matches. One engine, one instrument (OOD-2). */
  public Instrument instrument() {
    return instrument;
  }

  private SubmitResult reject(
      final long clientOrderId, final long orderId, final RejectReason reason) {
    notifyRejected(clientOrderId, orderId, reason);
    return new Rejected(clientOrderId, orderId, reason);
  }

  /**
   * A sink that feeds the caller's collector and every registered listener.
   *
   * <p>One trade, many consumers: the request/response DTO and the outbound feed see the same
   * executions with the same sequence numbers, which is what makes the two views reconcilable.
   */
  private TradeEventSink fanOutTo(final CollectingTradeSink collector) {
    return (sequence, aggressorId, restingId, price, qty) -> {
      collector.onTrade(sequence, aggressorId, restingId, price, qty);
      for (final EngineListener listener : listeners) {
        listener.onTrade(sequence, aggressorId, restingId, price, qty);
      }
    };
  }

  private void notifyAccepted(final long clientOrderId, final long orderId) {
    for (final EngineListener listener : listeners) listener.onAccepted(clientOrderId, orderId);
  }

  private void notifyRejected(
      final long clientOrderId, final long orderId, final RejectReason reason) {
    for (final EngineListener listener : listeners) {
      listener.onRejected(clientOrderId, orderId, reason);
    }
  }

  private void notifyTerminal(final long orderId, final Status status) {
    for (final EngineListener listener : listeners) listener.onTerminal(orderId, status);
  }

  /** Falls back to a placeholder so a malformed command can still be registered as rejected. */
  private static OrderSide sideOf(final NewOrder command) {
    return command.side() == null ? OrderSide.BUY : command.side();
  }

  private static OrderType typeOf(final NewOrder command) {
    return command.type() == null ? OrderType.LIMIT : command.type();
  }
}

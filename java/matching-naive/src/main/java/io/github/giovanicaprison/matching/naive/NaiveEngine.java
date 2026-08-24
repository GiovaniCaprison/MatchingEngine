package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.protocol.AllocationAlgorithm;
import io.github.giovanicaprison.matching.protocol.CancelOrderDecoder;
import io.github.giovanicaprison.matching.protocol.InstrumentDefinitionDecoder;
import io.github.giovanicaprison.matching.protocol.MassCancelDecoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import io.github.giovanicaprison.matching.protocol.NewOrderDecoder;
import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.RejectReason;
import io.github.giovanicaprison.matching.protocol.RemoveReason;
import io.github.giovanicaprison.matching.protocol.ReplaceOrderDecoder;
import io.github.giovanicaprison.matching.protocol.SessionState;
import io.github.giovanicaprison.matching.protocol.SessionStateChangeDecoder;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.agrona.DirectBuffer;

/**
 * Rung zero: correct, complete, and as slow as not thinking about representation makes it.
 *
 * <p>Every structural decision here is the obvious one. One list for the book and one for the
 * stops, an order object per command, a scan wherever a question is asked. What it is not is
 * careless: the remit is the whole remit, because the point of the rung is to be the same engine as
 * the ones above it with none of their machinery.
 *
 * <p>Validation happens once, at the top, before anything is touched (P-5). Below that everything
 * assumes valid input, which is what makes "was the book modified?" answerable at all: a refusal is
 * decided before the first mutation, so it never has to be undone.
 */
public final class NaiveEngine implements MatchingEngine {

  private final Feed feed;
  private final Book book = new Book();
  private final Triggers triggers = new Triggers();

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final InstrumentDefinitionDecoder definition = new InstrumentDefinitionDecoder();
  private final NewOrderDecoder newOrder = new NewOrderDecoder();
  private final CancelOrderDecoder cancelOrder = new CancelOrderDecoder();
  private final ReplaceOrderDecoder replaceOrder = new ReplaceOrderDecoder();
  private final MassCancelDecoder massCancel = new MassCancelDecoder();
  private final SessionStateChangeDecoder sessionState = new SessionStateChangeDecoder();

  private Instrument instrument;
  private SessionState state = SessionState.PRE_OPEN;
  private long reference;
  private long nextOrderId = 1;
  private long nextExecutionId = 1;
  private long arrival;
  private long indicativePrice;
  private long indicativeQuantity;

  NaiveEngine(final EventPublisher events) {
    this.feed = new Feed(events);
  }

  @Override
  public void onCommand(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    final int body = offset + MessageHeaderDecoder.ENCODED_LENGTH;
    final int block = header.blockLength();
    final int version = header.version();
    switch (header.templateId()) {
      case InstrumentDefinitionDecoder.TEMPLATE_ID -> {
        definition.wrap(buffer, body, block, version);
        define();
      }
      case NewOrderDecoder.TEMPLATE_ID -> {
        newOrder.wrap(buffer, body, block, version);
        enter();
      }
      case CancelOrderDecoder.TEMPLATE_ID -> {
        cancelOrder.wrap(buffer, body, block, version);
        cancel();
      }
      case ReplaceOrderDecoder.TEMPLATE_ID -> {
        replaceOrder.wrap(buffer, body, block, version);
        replace();
      }
      case MassCancelDecoder.TEMPLATE_ID -> {
        massCancel.wrap(buffer, body, block, version);
        massCancel();
      }
      case SessionStateChangeDecoder.TEMPLATE_ID -> {
        sessionState.wrap(buffer, body, block, version);
        changeState();
      }
      default ->
          throw new IllegalArgumentException(
              "template " + header.templateId() + " is not a command (P-14)");
    }
  }

  /** (FR-1.1) The instrument arrives once and configures everything after it. */
  private void define() {
    instrument = Instrument.of(definition);
    reference = instrument.openingReference();
    feed.instrument(instrument.id());
  }

  // Order entry ---------------------------------------------------------------------------------

  /** (FR-1.2, FR-1.3, FR-1.4) */
  private void enter() {
    final long clientOrderId = newOrder.clientOrderId();
    final int participantId = (int) newOrder.participantId();
    final RejectReason refusal = refusalFor();
    if (refusal != null) {
      feed.rejected(clientOrderId, participantId, refusal);
      return;
    }
    final Order order =
        new Order(
            nextOrderId++,
            clientOrderId,
            participantId,
            newOrder.side(),
            newOrder.pricing(),
            newOrder.timeInForce(),
            newOrder.flags().postOnly(),
            newOrder.price(),
            newOrder.quantity(),
            newOrder.minQuantity(),
            newOrder.displayQuantity(),
            newOrder.triggerPrice(),
            newOrder.smpId(),
            ++arrival);
    feed.accepted(order);
    admit(order);
  }

  /**
   * Places an admitted order: into the trigger book, into the book, or across it.
   *
   * <p>Shared by order entry and by the two other ways an order arrives at the book, a stop that
   * has fired and a replace that lost its queue position.
   */
  private void admit(final Order order) {
    if (order.stop()) {
      triggers.add(order);
      return;
    }
    if (matching()) {
      match(order);
      fireTriggers();
    }
    settle(order);
  }

  /** What becomes of whatever the walk left: the book, or a removal. */
  private void settle(final Order order) {
    if (order.remaining() == 0) {
      return;
    }
    if (order.restsOnRemainder()) {
      book.add(order);
      feed.rested(order);
      reportIndicative();
      return;
    }
    feed.removed(order.id(), order.remaining(), RemoveReason.IMMEDIATE_OR_CANCEL_REMAINDER);
  }

  // Matching ------------------------------------------------------------------------------------

  /** (FR-3.1) Best price first, one price level at a time, until nothing crosses. */
  private void match(final Order taker) {
    while (taker.remaining() > 0) {
      final Order next = book.nextToTake(taker.side(), limitOf(taker));
      if (next == null) {
        return;
      }
      if (prevented(taker, next)) {
        continue;
      }
      if (instrument.allocation() == AllocationAlgorithm.PRO_RATA) {
        proRata(taker, next.price());
      } else {
        take(taker, next);
      }
    }
  }

  /** (FR-3.7) The resting order goes and the walk continues into whatever was behind it. */
  private boolean prevented(final Order taker, final Order resting) {
    if (taker.smpId() == 0 || taker.smpId() != resting.smpId()) {
      return false;
    }
    book.remove(resting);
    feed.removed(resting.id(), resting.displayed(), RemoveReason.SELF_MATCH_PREVENTED);
    return true;
  }

  /** As much as the front of the queue can give, which is what price-time allocation is. */
  private void take(final Order taker, final Order resting) {
    takeExactly(taker, resting, Math.min(taker.remaining(), resting.displayed()));
  }

  /** (FR-3.5, FR-3.6) One execution, at the price the resting order named. */
  private void takeExactly(final Order taker, final Order resting, final long quantity) {
    final long price = resting.price();
    taker.take(quantity);
    final boolean replenishes = resting.take(quantity);
    feed.executed(nextExecutionId++, taker.id(), resting.id(), price, quantity);
    reference = price;
    if (resting.remaining() == 0) {
      // A resting order executed in full gets no removal event: a consumer tracking quantity has
      // already seen it reach zero.
      book.remove(resting);
      return;
    }
    if (replenishes) {
      // (FR-5.4) The next tranche joins the back of the queue at its price, which to a consumer is
      // indistinguishable from a new order arriving there. That is what an iceberg is for.
      resting.replenish(++arrival);
      feed.rested(resting);
    }
  }

  /**
   * (FR-3.2, FR-3.4) Pro-rata at one price: shares in proportion to resting quantity, rounded down
   * to a whole lot, and whatever rounding left over goes in arrival order.
   */
  private void proRata(final Order taker, final long price) {
    final List<Order> level = book.atPrice(Book.opposite(taker.side()), price);
    long available = 0;
    for (final Order resting : level) {
      available += resting.displayed();
    }
    if (available == 0) {
      return;
    }
    final long wanted = Math.min(taker.remaining(), available);
    final long lot = instrument.lotSize();
    for (final Order resting : level) {
      if (taker.remaining() == 0) {
        break;
      }
      final long share = wanted * resting.displayed() / available / lot * lot;
      final long quantity = Math.min(Math.min(share, resting.displayed()), taker.remaining());
      if (quantity > 0) {
        takeExactly(taker, resting, quantity);
      }
    }
    // Rounding leaves a remainder, and arrival order decides it. The same walk serves, since it
    // takes as much as the front of the queue can give.
    while (taker.remaining() > 0) {
      final Order next = book.nextToTake(taker.side(), limitOf(taker));
      if (next == null || next.price() != price) {
        return;
      }
      take(taker, next);
    }
  }

  private static long limitOf(final Order order) {
    return order.pricing() == PricingInstruction.MARKET ? 0 : order.price();
  }

  // Triggers ------------------------------------------------------------------------------------

  /**
   * (FR-6.4) A cascade runs to completion before the next command is applied.
   *
   * <p>Evaluated once the walk is over rather than between its executions, which is the same
   * answer: prices in a walk move away from the touch monotonically, so the last executed price is
   * the furthest one reached, and a stop that any price in the walk reached is a stop that price
   * reaches.
   */
  private void fireTriggers() {
    final Deque<Order> pending = new ArrayDeque<>(triggers.fire(reference));
    while (!pending.isEmpty()) {
      final Order fired = pending.removeFirst();
      feed.triggered(fired);
      final Order order = fired.triggered(++arrival);
      match(order);
      settle(order);
      pending.addAll(triggers.fire(reference));
    }
  }

  // Amend and cancel ----------------------------------------------------------------------------

  /** (FR-4.1, FR-4.2) */
  private void cancel() {
    if (state == SessionState.CLOSED) {
      feed.rejected(
          cancelOrder.clientOrderId(),
          (int) cancelOrder.participantId(),
          RejectReason.STATE_NOT_PERMITTED);
      return;
    }
    final int participantId = (int) cancelOrder.participantId();
    final long clientOrderId = cancelOrder.clientOrderId();
    final Order resting = book.named(participantId, clientOrderId);
    if (resting != null) {
      book.remove(resting);
      feed.removed(resting.id(), resting.displayed(), RemoveReason.CANCELLED);
      reportIndicative();
      return;
    }
    final Order stop = triggers.named(participantId, clientOrderId);
    if (stop != null) {
      // (FR-6.5) A stop is reported on cancellation as well, and it never appeared as resting, so
      // what it takes with it is its whole quantity.
      triggers.remove(stop);
      feed.removed(stop.id(), stop.remaining(), RemoveReason.CANCELLED);
      return;
    }
    feed.rejected(
        cancelOrder.clientOrderId(), (int) cancelOrder.participantId(), RejectReason.UNKNOWN_ORDER);
  }

  /** (FR-4.3, FR-4.4, FR-4.5, FR-4.6, FR-4.8) */
  private void replace() {
    final long clientOrderId = replaceOrder.clientOrderId();
    final int participantId = (int) replaceOrder.participantId();
    if (state == SessionState.CLOSED) {
      feed.rejected(clientOrderId, participantId, RejectReason.STATE_NOT_PERMITTED);
      return;
    }
    final Order resting = book.named(participantId, clientOrderId);
    if (resting == null) {
      feed.rejected(clientOrderId, participantId, RejectReason.UNKNOWN_ORDER);
      return;
    }
    final long quantity = replaceOrder.quantity();
    final long price = replaceOrder.price();
    final RejectReason refusal = refusalForReplace(resting, quantity, price);
    if (refusal != null) {
      feed.rejected(clientOrderId, participantId, refusal);
      return;
    }
    if (price == resting.price() && quantity < resting.remaining()) {
      // (FR-4.4, FR-8.5) Less at the same price keeps its place, so nothing leaves the book.
      resting.reduceTo(quantity);
      feed.reduced(resting);
      reportIndicative();
      return;
    }
    // (FR-4.5) Anything else is a removal and a fresh rest, and the id survives both (FR-4.8).
    book.remove(resting);
    feed.replaced(resting, resting.displayed());
    admit(replacement(resting, quantity, price));
  }

  private Order replacement(final Order original, final long quantity, final long price) {
    return new Order(
        original.id(),
        original.clientOrderId(),
        original.participantId(),
        original.side(),
        original.pricing(),
        original.timeInForce(),
        original.postOnly(),
        price,
        quantity,
        original.minQuantity(),
        original.iceberg() ? original.displayed() : 0,
        0,
        original.smpId(),
        ++arrival);
  }

  /** (FR-4.7) Everything for one participant, in arrival order, book and stops alike. */
  private void massCancel() {
    if (state == SessionState.CLOSED) {
      feed.rejected(
          massCancel.clientOrderId(),
          (int) massCancel.participantId(),
          RejectReason.STATE_NOT_PERMITTED);
      return;
    }
    final int participantId = (int) massCancel.participantId();
    final List<Order> resting = book.of(participantId);
    final List<Order> stops = triggers.of(participantId);
    final List<Order> everything = new java.util.ArrayList<>(resting);
    everything.addAll(stops);
    everything.sort((left, right) -> Long.compare(left.arrival(), right.arrival()));
    for (final Order order : everything) {
      if (order.stop()) {
        triggers.remove(order);
        feed.removed(order.id(), order.remaining(), RemoveReason.MASS_CANCELLED);
      } else {
        book.remove(order);
        feed.removed(order.id(), order.displayed(), RemoveReason.MASS_CANCELLED);
      }
    }
    reportIndicative();
  }

  // Trading state -------------------------------------------------------------------------------

  /** (FR-7.1, FR-7.2, FR-7.8) The state moves on a command and on nothing else. */
  private void changeState() {
    final SessionState entering = sessionState.state();
    if (callPhase(state) && entering != state) {
      // (FR-7.5, FR-7.6) Leaving a call phase is what runs the uncrossing, and its executions are
      // published before the state they belong to is left.
      uncross();
    }
    state = entering;
    feed.stateChanged(state);
    indicativePrice = 0;
    indicativeQuantity = 0;
    if (callPhase(state)) {
      reportIndicative();
    }
  }

  private void uncross() {
    final Auction.Uncrossing uncrossing = Auction.uncrossing(book, reference);
    if (!uncrossing.crosses()) {
      return;
    }
    final long price = uncrossing.price();
    long remaining = uncrossing.quantity();
    final List<Order> buys = willing(Side.BUY, price);
    final List<Order> sells = willing(Side.SELL, price);
    int sell = 0;
    for (final Order buy : buys) {
      while (buy.remaining() > 0 && remaining > 0 && sell < sells.size()) {
        final Order resting = sells.get(sell);
        // (FR-7.6) Everything trades at the one price the auction found.
        final long quantity = Math.min(Math.min(buy.remaining(), resting.remaining()), remaining);
        buy.take(quantity);
        resting.take(quantity);
        remaining -= quantity;
        feed.executed(nextExecutionId++, buy.id(), resting.id(), price, quantity);
        if (resting.remaining() == 0) {
          book.remove(resting);
          sell++;
        }
      }
      if (buy.remaining() == 0) {
        book.remove(buy);
      }
    }
    reference = price;
    fireTriggers();
  }

  /** Everyone who would trade at a price, earliest first. */
  private List<Order> willing(final Side side, final long price) {
    final List<Order> found = new java.util.ArrayList<>();
    for (final Order order : book.orders()) {
      if (order.side() != side) {
        continue;
      }
      final boolean would = side == Side.BUY ? order.price() >= price : order.price() <= price;
      if (would) {
        found.add(order);
      }
    }
    found.sort((left, right) -> Long.compare(left.arrival(), right.arrival()));
    return found;
  }

  /** (FR-7.7) Reported whenever it changes, and only while there is an auction to report on. */
  private void reportIndicative() {
    if (!callPhase(state)) {
      return;
    }
    final Auction.Uncrossing uncrossing = Auction.uncrossing(book, reference);
    if (uncrossing.price() == indicativePrice && uncrossing.quantity() == indicativeQuantity) {
      return;
    }
    indicativePrice = uncrossing.price();
    indicativeQuantity = uncrossing.quantity();
    feed.indicative(indicativePrice, indicativeQuantity);
  }

  /** (FR-7.4) Continuous matching happens in one state and nowhere else. */
  private boolean matching() {
    return state == SessionState.CONTINUOUS;
  }

  private static boolean callPhase(final SessionState state) {
    return state == SessionState.OPENING_AUCTION || state == SessionState.CLOSING_AUCTION;
  }

  // Validation ----------------------------------------------------------------------------------

  /**
   * Everything that can refuse an order, in one place and before any of it is applied.
   *
   * <p>Returns null when there is nothing wrong with it. The three checks that need the book come
   * last, because they are the expensive ones and because a malformed order should not be scanning
   * anything.
   */
  private RejectReason refusalFor() {
    if (state == SessionState.CLOSED) {
      return RejectReason.STATE_NOT_PERMITTED;
    }
    final long quantity = newOrder.quantity();
    if (quantity <= 0) {
      return RejectReason.NON_POSITIVE_QUANTITY;
    }
    if (quantity % instrument.lotSize() != 0) {
      return RejectReason.LOT_VIOLATION;
    }
    if (newOrder.minQuantity() > quantity) {
      return RejectReason.MINIMUM_QUANTITY_ABOVE_ORDER;
    }
    if (newOrder.displayQuantity() > quantity) {
      return RejectReason.DISPLAY_QUANTITY_ABOVE_ORDER;
    }
    final PricingInstruction pricing = newOrder.pricing();
    if (inconsistent(pricing, newOrder.timeInForce(), newOrder.flags().postOnly())) {
      return RejectReason.INVALID_FIELDS;
    }
    if (pricing == PricingInstruction.LIMIT) {
      final RejectReason price = refusalForPrice(newOrder.price());
      if (price != null) {
        return price;
      }
    }
    if (newOrder.triggerPrice() != 0) {
      final RejectReason trigger = refusalForTriggerPrice(newOrder.triggerPrice());
      if (trigger != null) {
        return trigger;
      }
    }
    return refusalFromTheBook(
        newOrder.side(),
        pricing,
        newOrder.timeInForce(),
        newOrder.flags().postOnly(),
        newOrder.price(),
        quantity,
        newOrder.minQuantity(),
        newOrder.smpId(),
        newOrder.triggerPrice());
  }

  private RejectReason refusalForPrice(final long price) {
    if (price <= 0) {
      return RejectReason.NON_POSITIVE_PRICE;
    }
    if (price % instrument.tickSize() != 0) {
      return RejectReason.TICK_VIOLATION;
    }
    if (price < instrument.minPrice() || price > instrument.maxPrice()) {
      return RejectReason.STATIC_BAND_VIOLATION;
    }
    if (Math.abs(price - reference) > instrument.bandWidth()) {
      return RejectReason.DYNAMIC_BAND_VIOLATION;
    }
    return null;
  }

  /**
   * A trigger price is a price on the instrument, so tick and bounds apply to it.
   *
   * <p>The dynamic band does not. A stop is placed away from where the market is, which is the
   * whole reason for having one, and banding it against the last executed price would refuse the
   * stops anybody actually sends.
   */
  private RejectReason refusalForTriggerPrice(final long price) {
    if (price <= 0) {
      return RejectReason.NON_POSITIVE_PRICE;
    }
    if (price % instrument.tickSize() != 0) {
      return RejectReason.TICK_VIOLATION;
    }
    if (price < instrument.minPrice() || price > instrument.maxPrice()) {
      return RejectReason.STATIC_BAND_VIOLATION;
    }
    return null;
  }

  /**
   * (VR-3.1) Combinations that contradict themselves.
   *
   * <p>A market order with a price of its own, one that is told to rest, and one told never to take
   * are each an instruction that cannot be followed. A display quantity is not on the list: an
   * order that never rests displays nothing, which is what it already does.
   */
  private static boolean inconsistent(
      final PricingInstruction pricing, final TimeInForce timeInForce, final boolean postOnly) {
    if (pricing == PricingInstruction.MARKET) {
      return postOnly
          || timeInForce == TimeInForce.GOOD_TILL_CANCEL
          || timeInForce == TimeInForce.DAY;
    }
    return postOnly
        && (timeInForce == TimeInForce.IMMEDIATE_OR_CANCEL
            || timeInForce == TimeInForce.FILL_OR_KILL);
  }

  /**
   * (FR-2.5, FR-2.4, FR-2.6) The three refusals that have to ask the book first.
   *
   * <p>All three are decided before anything is touched, which is why they are refusals and not
   * removals: nothing was executed and nothing rested.
   */
  private RejectReason refusalFromTheBook(
      final Side side,
      final PricingInstruction pricing,
      final TimeInForce timeInForce,
      final boolean postOnly,
      final long price,
      final long quantity,
      final long minQuantity,
      final long smpId,
      final long triggerPrice) {
    if (triggerPrice != 0 || !matching()) {
      // A stop is not going near the book yet, and outside continuous trading nothing executes on
      // entry, so a fill-or-kill or a minimum quantity cannot be satisfied.
      if (triggerPrice == 0 && timeInForce == TimeInForce.FILL_OR_KILL) {
        return RejectReason.FILL_OR_KILL_UNFILLABLE;
      }
      if (triggerPrice == 0 && minQuantity > 0) {
        return RejectReason.MINIMUM_QUANTITY_NOT_MET;
      }
      return null;
    }
    final long limit = pricing == PricingInstruction.MARKET ? 0 : price;
    if (postOnly && book.nextToTake(side, limit) != null) {
      return RejectReason.WOULD_CROSS;
    }
    if (timeInForce == TimeInForce.FILL_OR_KILL || minQuantity > 0) {
      final long fillable = book.fillable(side, limit, smpId);
      if (timeInForce == TimeInForce.FILL_OR_KILL && fillable < quantity) {
        return RejectReason.FILL_OR_KILL_UNFILLABLE;
      }
      if (minQuantity > 0 && fillable < minQuantity) {
        return RejectReason.MINIMUM_QUANTITY_NOT_MET;
      }
    }
    return null;
  }

  /** (FR-4.6) A replace a liquidity flag refuses leaves the original where it was. */
  private RejectReason refusalForReplace(
      final Order resting, final long quantity, final long price) {
    if (quantity <= 0) {
      return RejectReason.NON_POSITIVE_QUANTITY;
    }
    if (quantity % instrument.lotSize() != 0) {
      return RejectReason.LOT_VIOLATION;
    }
    final RejectReason refusal = refusalForPrice(price);
    if (refusal != null) {
      return refusal;
    }
    if (resting.postOnly() && matching() && book.nextToTake(resting.side(), price) != null) {
      return RejectReason.WOULD_CROSS;
    }
    return null;
  }
}

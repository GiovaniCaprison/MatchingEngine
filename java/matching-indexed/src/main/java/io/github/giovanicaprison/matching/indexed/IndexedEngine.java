package io.github.giovanicaprison.matching.indexed;

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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.agrona.DirectBuffer;

/**
 * Rung one: the naive engine's behaviour on an indexed book.
 *
 * <p>The step between this rung and the last isolates the book's structure and nothing else. The
 * commands flow through the same decisions in the same order, every event leaves in the same
 * sequence, and the differential holds the two byte identical; what changed is who pays for a
 * question. Finding the best price, the front of a queue, or the order a cancel names is a lookup
 * here and a walk there, and the price of the lookup is bookkeeping the invariants police (NFR-3.1,
 * NFR-3.2).
 *
 * <p>An order object per command stays, deliberately: allocation is the next rung's variable, so
 * removing it here would blur two steps of the ladder into one.
 */
public final class IndexedEngine implements MatchingEngine {

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
  private long lastExecuted;
  private long nextOrderId = 1;
  private long nextExecutionId = 1;
  private long arrival;
  private long indicativePrice;
  private long indicativeQuantity;

  IndexedEngine(final EventPublisher events) {
    this.feed = new Feed(events);
  }

  /** The book itself, for the invariants that hold its bookkeeping to its queues (NFR-3.1). */
  Book book() {
    return book;
  }

  List<Order> resting() {
    return book.orders();
  }

  List<Order> waiting() {
    return triggers.stops();
  }

  long lastExecutedPrice() {
    return lastExecuted;
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

  private void define() {
    instrument = Instrument.of(definition);
    reference = instrument.openingReference();
    feed.instrument(instrument.id());
  }

  // Order entry ---------------------------------------------------------------------------------

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

  private void admit(final Order order) {
    if (order.stop()) {
      triggers.add(order);
      fireTriggers();
      return;
    }
    if (matching()) {
      match(order);
      fireTriggers();
    }
    settle(order);
  }

  private void settle(final Order order) {
    if (order.remaining() == 0) {
      return;
    }
    if (order.restsOnRemainder()) {
      order.rest(++arrival);
      book.add(order);
      feed.rested(order);
      reportIndicative();
      return;
    }
    feed.removed(order.id(), order.remaining(), RemoveReason.IMMEDIATE_OR_CANCEL_REMAINDER);
  }

  // Matching ------------------------------------------------------------------------------------

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

  private boolean prevented(final Order taker, final Order resting) {
    if (taker.smpId() == 0 || taker.smpId() != resting.smpId()) {
      return false;
    }
    book.remove(resting);
    feed.removed(resting.id(), resting.displayed(), RemoveReason.SELF_MATCH_PREVENTED);
    return true;
  }

  private void take(final Order taker, final Order resting) {
    takeExactly(taker, resting, Math.min(taker.remaining(), resting.displayed()));
  }

  private void takeExactly(final Order taker, final Order resting, final long quantity) {
    final long price = resting.price();
    taker.take(quantity);
    final long shownBefore = resting.displayed();
    final boolean replenishes = resting.take(quantity);
    feed.executed(nextExecutionId++, taker.id(), resting.id(), price, quantity);
    reference = price;
    lastExecuted = price;
    if (resting.remaining() == 0) {
      book.displayedChanged(resting, shownBefore);
      book.remove(resting);
      return;
    }
    if (replenishes) {
      // One delta covers the take and the reveal: the level goes from holding what this order
      // showed before the execution to holding its fresh tranche.
      resting.rest(++arrival);
      book.requeued(resting, shownBefore);
      feed.rested(resting);
      return;
    }
    book.displayedChanged(resting, shownBefore);
  }

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

  private void fireTriggers() {
    if (lastExecuted == 0) {
      return;
    }
    final Deque<Order> pending = new ArrayDeque<>(triggers.fire(lastExecuted));
    while (!pending.isEmpty()) {
      final Order fired = pending.removeFirst();
      feed.triggered(fired);
      final Order order = fired.triggered(++arrival);
      if (matching()) {
        match(order);
      }
      settle(order);
      pending.addAll(triggers.fire(lastExecuted));
    }
  }

  // Amend and cancel ----------------------------------------------------------------------------

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
      triggers.remove(stop);
      feed.removed(stop.id(), stop.remaining(), RemoveReason.CANCELLED);
      return;
    }
    feed.rejected(
        cancelOrder.clientOrderId(), (int) cancelOrder.participantId(), RejectReason.UNKNOWN_ORDER);
  }

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
    final long remainder = quantity - resting.executed();
    if (price == resting.price() && remainder < resting.remaining()) {
      final long shownBefore = resting.displayed();
      resting.reduceTo(remainder);
      book.displayedChanged(resting, shownBefore);
      feed.reduced(resting);
      reportIndicative();
      return;
    }
    book.remove(resting);
    feed.replaced(resting, resting.displayed());
    admit(replacement(resting, remainder, price));
  }

  private Order replacement(final Order original, final long remainder, final long price) {
    return new Order(
        original.id(),
        original.clientOrderId(),
        original.participantId(),
        original.side(),
        original.pricing(),
        original.timeInForce(),
        original.postOnly(),
        price,
        remainder,
        original.minQuantity(),
        original.displaySize(),
        0,
        original.smpId(),
        ++arrival,
        original.executed());
  }

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
    final List<Order> everything = new ArrayList<>(resting);
    everything.addAll(stops);
    everything.sort(Order.BY_ARRIVAL);
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

  private void changeState() {
    final SessionState entering = sessionState.state();
    if (callPhase(state) && entering != state) {
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
    long left = uncrossing.quantity();
    final List<Order> buys = willing(Side.BUY, price);
    final List<Order> sells = willing(Side.SELL, price);
    int sell = 0;
    for (final Order buy : buys) {
      while (buy.remaining() > 0 && left > 0 && sell < sells.size()) {
        final Order resting = sells.get(sell);
        left -= cross(buy, resting, price, left);
        if (resting.remaining() == 0) {
          sell++;
        }
      }
    }
    reference = price;
    lastExecuted = price;
    fireTriggers();
  }

  private long cross(final Order buy, final Order sell, final long price, final long left) {
    final long quantity = Math.min(Math.min(buy.displayed(), sell.displayed()), left);
    final long buyShown = buy.displayed();
    final long sellShown = sell.displayed();
    final boolean buyReplenishes = buy.take(quantity);
    final boolean sellReplenishes = sell.take(quantity);
    feed.executed(nextExecutionId++, buy.id(), sell.id(), price, quantity);
    reveal(buy, buyReplenishes, buyShown);
    reveal(sell, sellReplenishes, sellShown);
    return quantity;
  }

  private void reveal(final Order order, final boolean replenishes, final long shownBefore) {
    if (order.remaining() == 0) {
      book.displayedChanged(order, shownBefore);
      book.remove(order);
    } else if (replenishes) {
      order.rest(++arrival);
      book.requeued(order, shownBefore);
      feed.rested(order);
    } else {
      book.displayedChanged(order, shownBefore);
    }
  }

  private List<Order> willing(final Side side, final long price) {
    final List<Order> found = new ArrayList<>();
    for (final Order order : book.orders()) {
      if (order.side() == side && order.willingAt(price)) {
        found.add(order);
      }
    }
    found.sort(Order.BY_ARRIVAL);
    return found;
  }

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

  private boolean matching() {
    return state == SessionState.CONTINUOUS;
  }

  private static boolean callPhase(final SessionState state) {
    return state == SessionState.OPENING_AUCTION || state == SessionState.CLOSING_AUCTION;
  }

  // Validation ----------------------------------------------------------------------------------

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
    final RejectReason onTheInstrument = refusalOnTheInstrument(price);
    if (onTheInstrument != null) {
      return onTheInstrument;
    }
    if (Math.abs(price - reference) > instrument.bandWidth()) {
      return RejectReason.DYNAMIC_BAND_VIOLATION;
    }
    return null;
  }

  private RejectReason refusalForTriggerPrice(final long price) {
    return refusalOnTheInstrument(price);
  }

  private RejectReason refusalOnTheInstrument(final long price) {
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

  private RejectReason refusalForReplace(
      final Order resting, final long quantity, final long price) {
    if (quantity <= 0) {
      return RejectReason.NON_POSITIVE_QUANTITY;
    }
    if (quantity <= resting.executed()) {
      return RejectReason.QUANTITY_BELOW_EXECUTED;
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

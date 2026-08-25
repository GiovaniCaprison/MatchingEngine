package io.github.giovanicaprison.matching.lean.naive;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
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
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;

/**
 * The second honest engine: limit and market orders, price-time, and nothing else.
 *
 * <p>Published matching engine figures are taken on books like this one, and METHODOLOGY's first
 * question is what the rest of a real venue's feature set costs when nobody uses it. A runtime flag
 * cannot answer that, because a disabled feature behind a branch still occupies the method, the
 * object layout and the inlining budget (P-16). So this engine is the arm where the features do not
 * exist: no trigger book consulted after an execution, no tranche arithmetic on a take, no self
 * match comparison per candidate, no allocation choice, no auction. Comparing it against the full
 * rung on flow that uses only this remit measures the cost of existence itself.
 *
 * <p>On that shared remit the two engines are byte identical by construction, and a test holds them
 * to it: an engine that answered differently would be measuring a behaviour difference and calling
 * it a feature cost.
 *
 * <p>Input is trusted the way every engine here trusts it (P-14). Flow at this composition never
 * carries a qualifier, so the fields this engine does not read are fields nothing sets.
 */
public final class LeanEngine implements MatchingEngine {

  private final Feed feed;
  private final Book book = new Book();

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final InstrumentDefinitionDecoder definition = new InstrumentDefinitionDecoder();
  private final NewOrderDecoder newOrder = new NewOrderDecoder();
  private final CancelOrderDecoder cancelOrder = new CancelOrderDecoder();
  private final ReplaceOrderDecoder replaceOrder = new ReplaceOrderDecoder();
  private final MassCancelDecoder massCancel = new MassCancelDecoder();
  private final SessionStateChangeDecoder sessionState = new SessionStateChangeDecoder();

  private long tickSize = 1;
  private long lotSize = 1;
  private long minPrice;
  private long maxPrice;
  private long bandWidth;
  private SessionState state = SessionState.PRE_OPEN;
  private long reference;
  private long nextOrderId = 1;
  private long nextExecutionId = 1;
  private long arrival;

  LeanEngine(final EventPublisher events) {
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
        state = sessionState.state();
        feed.stateChanged(state);
      }
      default ->
          throw new IllegalArgumentException(
              "template " + header.templateId() + " is not a command (P-14)");
    }
  }

  private void define() {
    tickSize = definition.tickSize();
    lotSize = definition.lotSize();
    minPrice = definition.minPrice();
    maxPrice = definition.maxPrice();
    bandWidth = definition.bandWidth();
    reference = definition.openingReference();
    feed.instrument((int) definition.frame().instrumentId());
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
            newOrder.price(),
            newOrder.quantity(),
            ++arrival,
            0);
    feed.accepted(order);
    if (matching()) {
      match(order);
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
      return;
    }
    feed.removed(order.id(), order.remaining(), RemoveReason.IMMEDIATE_OR_CANCEL_REMAINDER);
  }

  // Matching ------------------------------------------------------------------------------------

  /** (FR-3.1, FR-3.3) Best price first, then earliest arrival, until nothing crosses. */
  private void match(final Order taker) {
    while (taker.remaining() > 0) {
      final Order resting = book.nextToTake(taker.side(), limitOf(taker));
      if (resting == null) {
        return;
      }
      final long quantity = Math.min(taker.remaining(), resting.remaining());
      final long price = resting.price();
      taker.take(quantity);
      resting.take(quantity);
      feed.executed(nextExecutionId++, taker.id(), resting.id(), price, quantity);
      reference = price;
      if (resting.remaining() == 0) {
        book.remove(resting);
      }
    }
  }

  private static long limitOf(final Order order) {
    return order.pricing() == PricingInstruction.MARKET ? 0 : order.price();
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
    final Order resting =
        book.named((int) cancelOrder.participantId(), cancelOrder.clientOrderId());
    if (resting == null) {
      feed.rejected(
          cancelOrder.clientOrderId(),
          (int) cancelOrder.participantId(),
          RejectReason.UNKNOWN_ORDER);
      return;
    }
    book.remove(resting);
    feed.removed(resting.id(), resting.remaining(), RemoveReason.CANCELLED);
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
      resting.reduceTo(remainder);
      feed.reduced(resting);
      return;
    }
    book.remove(resting);
    feed.replaced(resting, resting.remaining());
    final Order replacement =
        new Order(
            resting.id(),
            resting.clientOrderId(),
            resting.participantId(),
            resting.side(),
            resting.pricing(),
            resting.timeInForce(),
            price,
            remainder,
            ++arrival,
            resting.executed());
    if (matching()) {
      match(replacement);
    }
    settle(replacement);
  }

  private void massCancel() {
    if (state == SessionState.CLOSED) {
      feed.rejected(
          massCancel.clientOrderId(),
          (int) massCancel.participantId(),
          RejectReason.STATE_NOT_PERMITTED);
      return;
    }
    final List<Order> everything = new ArrayList<>(book.of((int) massCancel.participantId()));
    for (final Order order : everything) {
      book.remove(order);
      feed.removed(order.id(), order.remaining(), RemoveReason.MASS_CANCELLED);
    }
  }

  private boolean matching() {
    return state == SessionState.CONTINUOUS;
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
    if (quantity % lotSize != 0) {
      return RejectReason.LOT_VIOLATION;
    }
    final PricingInstruction pricing = newOrder.pricing();
    final TimeInForce timeInForce = newOrder.timeInForce();
    if (pricing == PricingInstruction.MARKET
        && (timeInForce == TimeInForce.GOOD_TILL_CANCEL || timeInForce == TimeInForce.DAY)) {
      // (VR-3.1) A market order cannot rest, so it cannot be told to.
      return RejectReason.INVALID_FIELDS;
    }
    if (pricing == PricingInstruction.LIMIT) {
      return refusalForPrice(newOrder.price());
    }
    return null;
  }

  private RejectReason refusalForPrice(final long price) {
    if (price <= 0) {
      return RejectReason.NON_POSITIVE_PRICE;
    }
    if (price % tickSize != 0) {
      return RejectReason.TICK_VIOLATION;
    }
    if (price < minPrice || price > maxPrice) {
      return RejectReason.STATIC_BAND_VIOLATION;
    }
    if (Math.abs(price - reference) > bandWidth) {
      return RejectReason.DYNAMIC_BAND_VIOLATION;
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
    if (quantity % lotSize != 0) {
      return RejectReason.LOT_VIOLATION;
    }
    return refusalForPrice(price);
  }
}

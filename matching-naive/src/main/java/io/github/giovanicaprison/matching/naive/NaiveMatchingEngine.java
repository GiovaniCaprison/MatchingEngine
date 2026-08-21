package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.api.EventSink;
import io.github.giovanicaprison.matching.api.Instrument;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.protocol.CancelOrderDecoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderEncoder;
import io.github.giovanicaprison.matching.protocol.NewOrderDecoder;
import io.github.giovanicaprison.matching.protocol.OrderAcceptedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderExecutedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderReducedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderRejectedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderRemovedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderRestedEncoder;
import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.RejectReason;
import io.github.giovanicaprison.matching.protocol.RemoveReason;
import io.github.giovanicaprison.matching.protocol.ReplaceOrderDecoder;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The obvious implementation: one list of resting orders, scanned.
 *
 * <p>Bottom of the ladder, and it has two jobs. It is the baseline every later implementation is
 * measured against, so it has to be the honest naive thing rather than a half optimised one. And it
 * is the reference the faster implementations are compared against, so it has to be simple enough
 * that when it and a faster engine disagree, the faster one is wrong.
 *
 * <p>Everything is linear in the number of resting orders. There are no price levels, no index by
 * order id, and no attempt to avoid work. A command is copied into an object, an order is allocated
 * per acceptance, and finding the best crossing order is a full scan.
 *
 * <p>Market orders are handled by an explicit branch rather than by giving them a sentinel price.
 * The sentinel trick is faster and it couples two distant pieces of code, so it belongs to an
 * implementation that is buying something with it.
 */
public final class NaiveMatchingEngine implements MatchingEngine {

  /** Enough for any single event this schema defines. */
  private static final int EVENT_BUFFER_CAPACITY = 128;

  private final Instrument instrument;
  private final EventSink sink;
  private final List<Order> resting = new ArrayList<>();

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final NewOrderDecoder newOrder = new NewOrderDecoder();
  private final CancelOrderDecoder cancelOrder = new CancelOrderDecoder();
  private final ReplaceOrderDecoder replaceOrder = new ReplaceOrderDecoder();

  private final UnsafeBuffer eventBuffer = new UnsafeBuffer(new byte[EVENT_BUFFER_CAPACITY]);
  private final MessageHeaderEncoder eventHeader = new MessageHeaderEncoder();
  private final OrderAcceptedEncoder accepted = new OrderAcceptedEncoder();
  private final OrderRejectedEncoder rejected = new OrderRejectedEncoder();
  private final OrderRestedEncoder rested = new OrderRestedEncoder();
  private final OrderExecutedEncoder executed = new OrderExecutedEncoder();
  private final OrderReducedEncoder reduced = new OrderReducedEncoder();
  private final OrderRemovedEncoder removed = new OrderRemovedEncoder();

  private long nextOrderId;
  private long nextExecutionId;
  private long nextEventSequence;

  public NaiveMatchingEngine(final Instrument instrument, final EventSink sink) {
    this.instrument = instrument;
    this.sink = sink;
  }

  @Override
  public void onCommand(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    final int bodyOffset = offset + header.encodedLength();
    final int blockLength = header.blockLength();
    final int version = header.version();

    switch (header.templateId()) {
      case NewOrderDecoder.TEMPLATE_ID -> {
        newOrder.wrap(buffer, bodyOffset, blockLength, version);
        onNewOrder(newOrder);
      }
      case CancelOrderDecoder.TEMPLATE_ID -> {
        cancelOrder.wrap(buffer, bodyOffset, blockLength, version);
        onCancel(cancelOrder);
      }
      case ReplaceOrderDecoder.TEMPLATE_ID -> {
        replaceOrder.wrap(buffer, bodyOffset, blockLength, version);
        onReplace(replaceOrder);
      }
      default -> throw new IllegalArgumentException("unknown template id " + header.templateId());
    }
  }

  private void onNewOrder(final NewOrderDecoder command) {
    final long cause = command.frame().sequence();
    final long clientOrderId = command.clientOrderId();
    final long participantId = command.participantId();
    final Side side = command.side();
    final PricingInstruction pricing = command.pricing();
    final TimeInForce timeInForce = command.timeInForce();
    final boolean postOnly = command.flags().postOnly();
    final long price = command.price();
    final long quantity = command.quantity();

    final RejectReason invalid = validate(pricing, timeInForce, postOnly, price, quantity);
    if (invalid != null) {
      emitRejected(cause, clientOrderId, participantId, invalid);
      return;
    }

    // Pre-trade gates. Both refuse before anything is touched, so the book is unchanged (VR-5.1).
    //
    // Only fill-or-kill and post-only need to know what is fillable, and finding out is a full
    // scan, so an ordinary limit order must not pay for it. Computing it unconditionally would be a
    // second scan per order that no reasonable naive engine performs, and this one is the baseline
    // every later implementation is measured against.
    if (timeInForce == TimeInForce.FILL_OR_KILL || postOnly) {
      final long fillable = fillableQuantity(side, pricing, price, quantity);
      if (timeInForce == TimeInForce.FILL_OR_KILL && fillable < quantity) {
        emitRejected(cause, clientOrderId, participantId, RejectReason.FILL_OR_KILL_UNFILLABLE);
        return;
      }
      if (postOnly && fillable > 0) {
        emitRejected(cause, clientOrderId, participantId, RejectReason.WOULD_CROSS);
        return;
      }
    }

    final long orderId = ++nextOrderId;
    emitAccepted(cause, orderId, clientOrderId, participantId);

    final long remaining = walk(cause, orderId, side, pricing, price, quantity);
    if (remaining == 0) {
      // No removal event: a consumer tracking quantity has already seen it reach zero.
      return;
    }

    if (rests(pricing, timeInForce)) {
      resting.add(new Order(orderId, clientOrderId, participantId, side, price, remaining));
      emitRested(cause, orderId, side, price, remaining);
    } else {
      emitRemoved(cause, orderId, remaining, RemoveReason.IMMEDIATE_OR_CANCEL_REMAINDER);
    }
  }

  private void onCancel(final CancelOrderDecoder command) {
    final long cause = command.frame().sequence();
    final long orderId = command.orderId();

    final Order order = find(orderId);
    if (order == null) {
      emitRejected(
          cause, command.clientOrderId(), command.participantId(), RejectReason.UNKNOWN_ORDER);
      return;
    }

    resting.remove(order);
    emitRemoved(cause, orderId, order.remainingQuantity, RemoveReason.CANCELLED);
  }

  private void onReplace(final ReplaceOrderDecoder command) {
    final long cause = command.frame().sequence();
    final long orderId = command.orderId();
    final long newQuantity = command.quantity();
    final long newPrice = command.price();

    final Order order = find(orderId);
    if (order == null) {
      emitRejected(
          cause, command.clientOrderId(), command.participantId(), RejectReason.UNKNOWN_ORDER);
      return;
    }

    final RejectReason invalid =
        validate(
            PricingInstruction.LIMIT, TimeInForce.GOOD_TILL_CANCEL, false, newPrice, newQuantity);
    if (invalid != null) {
      emitRejected(cause, order.clientOrderId, order.participantId, invalid);
      return;
    }

    // Lowering quantity at the same price takes nothing from anyone queued behind, so there is no
    // fairness argument for making it re-queue (FR-4.4).
    if (newPrice == order.price && newQuantity < order.remainingQuantity) {
      order.remainingQuantity = newQuantity;
      emitReduced(cause, orderId, newQuantity);
      return;
    }

    // Anything else is a fresh arrival, which is how ITCH models it and why the layer above needs
    // no special handling for replace (FR-4.5).
    resting.remove(order);
    emitRemoved(cause, orderId, order.remainingQuantity, RemoveReason.REPLACED);

    final long remaining =
        walk(cause, orderId, order.side, PricingInstruction.LIMIT, newPrice, newQuantity);
    if (remaining == 0) return;

    resting.add(
        new Order(
            orderId, order.clientOrderId, order.participantId, order.side, newPrice, remaining));
    emitRested(cause, orderId, order.side, newPrice, remaining);
  }

  /**
   * Consumes crossing liquidity best price first, earliest arrival first, and returns what is left.
   */
  private long walk(
      final long cause,
      final long aggressorId,
      final Side side,
      final PricingInstruction pricing,
      final long price,
      final long quantity) {

    long remaining = quantity;
    while (remaining > 0) {
      final Order best = bestCrossing(side, pricing, price);
      if (best == null) break;

      final long traded = Math.min(remaining, best.remainingQuantity);
      best.remainingQuantity -= traded;
      remaining -= traded;

      // The execution is at the resting order's price: price improvement accrues to the aggressor
      // (FR-3.3).
      emitExecuted(cause, aggressorId, best.orderId, best.price, traded);

      if (best.remainingQuantity == 0) resting.remove(best);
    }
    return remaining;
  }

  /**
   * The best crossing order, or {@code null} if none crosses.
   *
   * <p>Improvement is strict, so an equal price keeps the order found earlier. Arrival order is
   * list order, which is how time priority falls out without a queue (FR-3.2).
   */
  private Order bestCrossing(final Side side, final PricingInstruction pricing, final long price) {
    Order best = null;
    for (final Order candidate : resting) {
      if (!crosses(side, pricing, price, candidate)) continue;
      if (best == null || betterOnSide(candidate.side, candidate.price, best.price)) {
        best = candidate;
      }
    }
    return best;
  }

  private long fillableQuantity(
      final Side side, final PricingInstruction pricing, final long price, final long wanted) {
    long found = 0;
    for (final Order candidate : resting) {
      if (crosses(side, pricing, price, candidate)) found += candidate.remainingQuantity;
      if (found >= wanted) return wanted;
    }
    return found;
  }

  private static boolean crosses(
      final Side side, final PricingInstruction pricing, final long price, final Order candidate) {
    if (candidate.side == side) return false;
    if (pricing == PricingInstruction.MARKET) return true;
    return side == Side.BUY ? price >= candidate.price : price <= candidate.price;
  }

  /** Whether one price beats another for an order resting on the given side. */
  private static boolean betterOnSide(final Side side, final long candidate, final long best) {
    return side == Side.BUY ? candidate > best : candidate < best;
  }

  private static boolean rests(final PricingInstruction pricing, final TimeInForce timeInForce) {
    if (pricing == PricingInstruction.MARKET) return false;
    return timeInForce == TimeInForce.GOOD_TILL_CANCEL || timeInForce == TimeInForce.DAY;
  }

  private RejectReason validate(
      final PricingInstruction pricing,
      final TimeInForce timeInForce,
      final boolean postOnly,
      final long price,
      final long quantity) {

    if (quantity <= 0) return RejectReason.NON_POSITIVE_QUANTITY;
    if (quantity % instrument.lotSize() != 0) return RejectReason.LOT_VIOLATION;

    if (pricing == PricingInstruction.MARKET) {
      // A market order has no price to check. It also cannot rest and cannot avoid taking, so a
      // resting time in force or a post-only flag contradicts it (VR-3.1).
      final boolean canRest =
          timeInForce == TimeInForce.GOOD_TILL_CANCEL || timeInForce == TimeInForce.DAY;
      if (canRest || postOnly) return RejectReason.INVALID_FIELDS;
      return null;
    }

    if (price <= 0) return RejectReason.NON_POSITIVE_PRICE;
    if (price % instrument.tickSize() != 0) return RejectReason.TICK_VIOLATION;
    if (price < instrument.minPrice() || price > instrument.maxPrice()) {
      return RejectReason.BAND_VIOLATION;
    }
    return null;
  }

  private Order find(final long orderId) {
    for (final Order candidate : resting) {
      if (candidate.orderId == orderId) return candidate;
    }
    return null;
  }

  private void emitAccepted(
      final long cause, final long orderId, final long clientOrderId, final long participantId) {
    accepted.wrapAndApplyHeader(eventBuffer, 0, eventHeader);
    accepted
        .frame()
        .instrumentId(instrument.instrumentId())
        .sequence(++nextEventSequence)
        .causeSequence(cause);
    accepted.orderId(orderId).clientOrderId(clientOrderId).participantId(participantId);
    publish(eventHeader.encodedLength() + accepted.encodedLength());
  }

  private void emitRejected(
      final long cause,
      final long clientOrderId,
      final long participantId,
      final RejectReason reason) {
    rejected.wrapAndApplyHeader(eventBuffer, 0, eventHeader);
    rejected
        .frame()
        .instrumentId(instrument.instrumentId())
        .sequence(++nextEventSequence)
        .causeSequence(cause);
    rejected.clientOrderId(clientOrderId).participantId(participantId).reason(reason);
    publish(eventHeader.encodedLength() + rejected.encodedLength());
  }

  private void emitRested(
      final long cause,
      final long orderId,
      final Side side,
      final long price,
      final long quantity) {
    rested.wrapAndApplyHeader(eventBuffer, 0, eventHeader);
    rested
        .frame()
        .instrumentId(instrument.instrumentId())
        .sequence(++nextEventSequence)
        .causeSequence(cause);
    rested.orderId(orderId).side(side).price(price).quantity(quantity);
    publish(eventHeader.encodedLength() + rested.encodedLength());
  }

  private void emitExecuted(
      final long cause,
      final long aggressorOrderId,
      final long restingOrderId,
      final long price,
      final long quantity) {
    executed.wrapAndApplyHeader(eventBuffer, 0, eventHeader);
    executed
        .frame()
        .instrumentId(instrument.instrumentId())
        .sequence(++nextEventSequence)
        .causeSequence(cause);
    executed
        .executionId(++nextExecutionId)
        .aggressorOrderId(aggressorOrderId)
        .restingOrderId(restingOrderId)
        .price(price)
        .quantity(quantity);
    publish(eventHeader.encodedLength() + executed.encodedLength());
  }

  private void emitReduced(final long cause, final long orderId, final long quantity) {
    reduced.wrapAndApplyHeader(eventBuffer, 0, eventHeader);
    reduced
        .frame()
        .instrumentId(instrument.instrumentId())
        .sequence(++nextEventSequence)
        .causeSequence(cause);
    reduced.orderId(orderId).quantity(quantity);
    publish(eventHeader.encodedLength() + reduced.encodedLength());
  }

  private void emitRemoved(
      final long cause, final long orderId, final long quantity, final RemoveReason reason) {
    removed.wrapAndApplyHeader(eventBuffer, 0, eventHeader);
    removed
        .frame()
        .instrumentId(instrument.instrumentId())
        .sequence(++nextEventSequence)
        .causeSequence(cause);
    removed.orderId(orderId).quantity(quantity).reason(reason);
    publish(eventHeader.encodedLength() + removed.encodedLength());
  }

  private void publish(final int length) {
    sink.onEvent(eventBuffer, 0, length);
  }
}

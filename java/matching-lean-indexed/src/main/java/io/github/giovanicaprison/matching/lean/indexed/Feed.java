package io.github.giovanicaprison.matching.lean.indexed;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.protocol.MessageHeaderEncoder;
import io.github.giovanicaprison.matching.protocol.OrderAcceptedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderExecutedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderReducedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderRejectedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderRemovedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderRestedEncoder;
import io.github.giovanicaprison.matching.protocol.RejectReason;
import io.github.giovanicaprison.matching.protocol.RemoveReason;
import io.github.giovanicaprison.matching.protocol.SessionState;
import io.github.giovanicaprison.matching.protocol.SessionStateChangedEncoder;

/**
 * The output this engine can produce, which is less than the protocol defines.
 *
 * <p>No trigger event and no indicative, because nothing here triggers or uncrosses. The encoders
 * that are absent are absent from the object layout too, which is the same claim the whole engine
 * makes: a feature that does not exist costs nothing here, and comparing this against the full rung
 * on the same flow measures what its existing costs there (P-16).
 */
final class Feed {

  private final EventPublisher events;
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final OrderAcceptedEncoder accepted = new OrderAcceptedEncoder();
  private final OrderRejectedEncoder rejected = new OrderRejectedEncoder();
  private final OrderRestedEncoder rested = new OrderRestedEncoder();
  private final OrderExecutedEncoder executed = new OrderExecutedEncoder();
  private final OrderReducedEncoder reduced = new OrderReducedEncoder();
  private final OrderRemovedEncoder removed = new OrderRemovedEncoder();
  private final SessionStateChangedEncoder stateChanged = new SessionStateChangedEncoder();

  private int instrumentId;
  private long sequence;

  Feed(final EventPublisher events) {
    this.events = events;
  }

  void instrument(final int id) {
    this.instrumentId = id;
  }

  void accepted(final Order order) {
    final int at = claim(OrderAcceptedEncoder.BLOCK_LENGTH);
    accepted.wrapAndApplyHeader(events.buffer(), at, header);
    accepted.frame().instrumentId(instrumentId).sequence(++sequence);
    accepted
        .orderId(order.id())
        .clientOrderId(order.clientOrderId())
        .participantId(order.participantId());
    events.commit();
  }

  void rejected(final long clientOrderId, final int participantId, final RejectReason reason) {
    final int at = claim(OrderRejectedEncoder.BLOCK_LENGTH);
    rejected.wrapAndApplyHeader(events.buffer(), at, header);
    rejected.frame().instrumentId(instrumentId).sequence(++sequence);
    rejected.clientOrderId(clientOrderId).participantId(participantId).reason(reason);
    events.commit();
  }

  void rested(final Order order) {
    final int at = claim(OrderRestedEncoder.BLOCK_LENGTH);
    rested.wrapAndApplyHeader(events.buffer(), at, header);
    rested.frame().instrumentId(instrumentId).sequence(++sequence);
    rested.orderId(order.id()).side(order.side()).price(order.price()).quantity(order.remaining());
    events.commit();
  }

  void executed(
      final long executionId,
      final long aggressor,
      final long resting,
      final long price,
      final long quantity) {
    final int at = claim(OrderExecutedEncoder.BLOCK_LENGTH);
    executed.wrapAndApplyHeader(events.buffer(), at, header);
    executed.frame().instrumentId(instrumentId).sequence(++sequence);
    executed
        .executionId(executionId)
        .aggressorOrderId(aggressor)
        .restingOrderId(resting)
        .price(price)
        .quantity(quantity);
    events.commit();
  }

  void reduced(final Order order) {
    final int at = claim(OrderReducedEncoder.BLOCK_LENGTH);
    reduced.wrapAndApplyHeader(events.buffer(), at, header);
    reduced.frame().instrumentId(instrumentId).sequence(++sequence);
    reduced.orderId(order.id()).quantity(order.remaining());
    events.commit();
  }

  void removed(final long orderId, final long quantity, final RemoveReason reason) {
    final int at = claim(OrderRemovedEncoder.BLOCK_LENGTH);
    removed.wrapAndApplyHeader(events.buffer(), at, header);
    removed.frame().instrumentId(instrumentId).sequence(++sequence);
    removed.orderId(orderId).quantity(quantity).reason(reason);
    events.commit();
  }

  void stateChanged(final SessionState state) {
    final int at = claim(SessionStateChangedEncoder.BLOCK_LENGTH);
    stateChanged.wrapAndApplyHeader(events.buffer(), at, header);
    stateChanged.frame().instrumentId(instrumentId).sequence(++sequence);
    stateChanged.state(state);
    events.commit();
  }

  void replaced(final Order order, final long quantityRemoved) {
    removed(order.id(), quantityRemoved, RemoveReason.REPLACED);
  }

  private int claim(final int blockLength) {
    return events.claim(MessageHeaderEncoder.ENCODED_LENGTH + blockLength);
  }
}

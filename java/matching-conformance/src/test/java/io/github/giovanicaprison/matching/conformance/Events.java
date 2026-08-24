package io.github.giovanicaprison.matching.conformance;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.protocol.MessageHeaderEncoder;
import io.github.giovanicaprison.matching.protocol.OrderAcceptedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderExecutedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderRemovedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderRestedEncoder;
import io.github.giovanicaprison.matching.protocol.RemoveReason;
import io.github.giovanicaprison.matching.protocol.SessionState;
import io.github.giovanicaprison.matching.protocol.SessionStateChangedEncoder;
import io.github.giovanicaprison.matching.protocol.Side;
import java.util.function.Consumer;

/**
 * Encoded events for a scripted engine to emit.
 *
 * <p>The runner is what is being tested here, so the events are written by hand. An engine would be
 * the obvious thing to test against and the wrong one: a bug shared between the engine and the
 * runner would then read as a pass.
 */
final class Events {

  private static final int INSTRUMENT_ID = 1;

  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final OrderAcceptedEncoder accepted = new OrderAcceptedEncoder();
  private final OrderRestedEncoder rested = new OrderRestedEncoder();
  private final OrderExecutedEncoder executed = new OrderExecutedEncoder();
  private final OrderRemovedEncoder removed = new OrderRemovedEncoder();
  private final SessionStateChangedEncoder state = new SessionStateChangedEncoder();

  private long sequence;

  Consumer<EventPublisher> accepted(final long orderId, final long clientOrderId) {
    return events -> {
      final int at = claim(events, OrderAcceptedEncoder.BLOCK_LENGTH);
      accepted.wrapAndApplyHeader(events.buffer(), at, header);
      accepted.frame().instrumentId(INSTRUMENT_ID).sequence(++sequence);
      accepted.orderId(orderId).clientOrderId(clientOrderId).participantId(1);
      events.commit();
    };
  }

  Consumer<EventPublisher> rested(
      final long orderId, final Side side, final long price, final long quantity) {
    return events -> {
      final int at = claim(events, OrderRestedEncoder.BLOCK_LENGTH);
      rested.wrapAndApplyHeader(events.buffer(), at, header);
      rested.frame().instrumentId(INSTRUMENT_ID).sequence(++sequence);
      rested.orderId(orderId).side(side).price(price).quantity(quantity);
      events.commit();
    };
  }

  Consumer<EventPublisher> executed(
      final long executionId,
      final long aggressor,
      final long resting,
      final long price,
      final long quantity) {
    return events -> {
      final int at = claim(events, OrderExecutedEncoder.BLOCK_LENGTH);
      executed.wrapAndApplyHeader(events.buffer(), at, header);
      executed.frame().instrumentId(INSTRUMENT_ID).sequence(++sequence);
      executed
          .executionId(executionId)
          .aggressorOrderId(aggressor)
          .restingOrderId(resting)
          .price(price)
          .quantity(quantity);
      events.commit();
    };
  }

  Consumer<EventPublisher> removed(
      final long orderId, final long quantity, final RemoveReason reason) {
    return events -> {
      final int at = claim(events, OrderRemovedEncoder.BLOCK_LENGTH);
      removed.wrapAndApplyHeader(events.buffer(), at, header);
      removed.frame().instrumentId(INSTRUMENT_ID).sequence(++sequence);
      removed.orderId(orderId).quantity(quantity).reason(reason);
      events.commit();
    };
  }

  Consumer<EventPublisher> state(final SessionState session) {
    return events -> {
      final int at = claim(events, SessionStateChangedEncoder.BLOCK_LENGTH);
      state.wrapAndApplyHeader(events.buffer(), at, header);
      state.frame().instrumentId(INSTRUMENT_ID).sequence(++sequence);
      state.state(session);
      events.commit();
    };
  }

  /** An engine claims header and block together, since it knows both before it encodes either. */
  private static int claim(final EventPublisher events, final int blockLength) {
    return events.claim(MessageHeaderEncoder.ENCODED_LENGTH + blockLength);
  }
}

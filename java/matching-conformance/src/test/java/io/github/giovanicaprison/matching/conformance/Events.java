package io.github.giovanicaprison.matching.conformance;

import io.github.giovanicaprison.matching.api.EventSink;
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
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Encoded events for a scripted engine to emit.
 *
 * <p>The runner is what is being tested here, so the events are written by hand. An engine would be
 * the obvious thing to test against and the wrong one: a bug shared between the engine and the
 * runner would then read as a pass.
 */
final class Events {

  private static final int INSTRUMENT_ID = 1;

  private final MutableDirectBuffer buffer = new ExpandableArrayBuffer(256);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final OrderAcceptedEncoder accepted = new OrderAcceptedEncoder();
  private final OrderRestedEncoder rested = new OrderRestedEncoder();
  private final OrderExecutedEncoder executed = new OrderExecutedEncoder();
  private final OrderRemovedEncoder removed = new OrderRemovedEncoder();
  private final SessionStateChangedEncoder state = new SessionStateChangedEncoder();

  private long sequence;

  Consumer<EventSink> accepted(final long orderId, final long clientOrderId) {
    return sink -> {
      accepted.wrapAndApplyHeader(buffer, 0, header);
      accepted.frame().instrumentId(INSTRUMENT_ID).sequence(++sequence);
      accepted.orderId(orderId).clientOrderId(clientOrderId).participantId(1);
      emit(sink, accepted.encodedLength());
    };
  }

  Consumer<EventSink> rested(
      final long orderId, final Side side, final long price, final long quantity) {
    return sink -> {
      rested.wrapAndApplyHeader(buffer, 0, header);
      rested.frame().instrumentId(INSTRUMENT_ID).sequence(++sequence);
      rested.orderId(orderId).side(side).price(price).quantity(quantity);
      emit(sink, rested.encodedLength());
    };
  }

  Consumer<EventSink> executed(
      final long executionId,
      final long aggressor,
      final long resting,
      final long price,
      final long quantity) {
    return sink -> {
      executed.wrapAndApplyHeader(buffer, 0, header);
      executed.frame().instrumentId(INSTRUMENT_ID).sequence(++sequence);
      executed
          .executionId(executionId)
          .aggressorOrderId(aggressor)
          .restingOrderId(resting)
          .price(price)
          .quantity(quantity);
      emit(sink, executed.encodedLength());
    };
  }

  Consumer<EventSink> removed(final long orderId, final long quantity, final RemoveReason reason) {
    return sink -> {
      removed.wrapAndApplyHeader(buffer, 0, header);
      removed.frame().instrumentId(INSTRUMENT_ID).sequence(++sequence);
      removed.orderId(orderId).quantity(quantity).reason(reason);
      emit(sink, removed.encodedLength());
    };
  }

  Consumer<EventSink> state(final SessionState session) {
    return sink -> {
      state.wrapAndApplyHeader(buffer, 0, header);
      state.frame().instrumentId(INSTRUMENT_ID).sequence(++sequence);
      state.state(session);
      emit(sink, state.encodedLength());
    };
  }

  private void emit(final EventSink sink, final int encodedLength) {
    sink.onEvent(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + encodedLength);
  }
}

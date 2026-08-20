package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.api.EventSink;
import io.github.giovanicaprison.matching.api.Instrument;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.protocol.CancelOrderEncoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderEncoder;
import io.github.giovanicaprison.matching.protocol.NewOrderEncoder;
import io.github.giovanicaprison.matching.protocol.OrderAcceptedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderExecutedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderReducedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRejectedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRemovedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRestedDecoder;
import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.ReplaceOrderEncoder;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Drives an engine through the real interface and renders its events as text.
 *
 * <p>Text because an assertion on a decoded field tells you a number was wrong, and an assertion on
 * a line tells you which event was wrong. It also prefigures the conformance runner, which compares
 * exactly these lines across implementations, so this moves out of here once that module exists.
 */
final class EngineHarness {

  private final MatchingEngine engine;
  private final UnsafeBuffer commands = new UnsafeBuffer(new byte[256]);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final Capture capture = new Capture();

  private long sequence;
  private long clientOrderId;

  EngineHarness(final Instrument instrument) {
    this.engine = new NaiveMatchingEngine(instrument, capture);
  }

  /** A limit order that rests until cancelled. */
  long limit(final Side side, final long price, final long quantity) {
    return newOrder(
        side, PricingInstruction.LIMIT, TimeInForce.GOOD_TILL_CANCEL, false, price, quantity);
  }

  long newOrder(
      final Side side,
      final PricingInstruction pricing,
      final TimeInForce timeInForce,
      final boolean postOnly,
      final long price,
      final long quantity) {

    final NewOrderEncoder encoder = new NewOrderEncoder();
    encoder.wrapAndApplyHeader(commands, 0, header);
    encoder.frame().instrumentId(1).sequence(++sequence);
    final long client = ++clientOrderId;
    encoder
        .clientOrderId(client)
        .participantId(1)
        .side(side)
        .pricing(pricing)
        .timeInForce(timeInForce)
        .price(price)
        .quantity(quantity);
    encoder.flags().postOnly(postOnly);
    return dispatch(header.encodedLength() + encoder.encodedLength());
  }

  void cancel(final long orderId) {
    final CancelOrderEncoder encoder = new CancelOrderEncoder();
    encoder.wrapAndApplyHeader(commands, 0, header);
    encoder.frame().instrumentId(1).sequence(++sequence);
    encoder.clientOrderId(++clientOrderId).participantId(1).orderId(orderId);
    dispatch(header.encodedLength() + encoder.encodedLength());
  }

  void replace(final long orderId, final long quantity, final long price) {
    final ReplaceOrderEncoder encoder = new ReplaceOrderEncoder();
    encoder.wrapAndApplyHeader(commands, 0, header);
    encoder.frame().instrumentId(1).sequence(++sequence);
    encoder
        .clientOrderId(++clientOrderId)
        .participantId(1)
        .orderId(orderId)
        .quantity(quantity)
        .price(price);
    dispatch(header.encodedLength() + encoder.encodedLength());
  }

  /** Events from the most recent command only, which is what a test almost always wants. */
  List<String> events() {
    return List.copyOf(capture.latest);
  }

  private long dispatch(final int length) {
    capture.latest.clear();
    engine.onCommand(commands, 0, length);
    return capture.lastOrderId;
  }

  /** Decodes each event into one line. */
  private static final class Capture implements EventSink {

    private final List<String> latest = new ArrayList<>();
    private final MessageHeaderDecoder header = new MessageHeaderDecoder();
    private final OrderAcceptedDecoder accepted = new OrderAcceptedDecoder();
    private final OrderRejectedDecoder rejected = new OrderRejectedDecoder();
    private final OrderRestedDecoder rested = new OrderRestedDecoder();
    private final OrderExecutedDecoder executed = new OrderExecutedDecoder();
    private final OrderReducedDecoder reduced = new OrderReducedDecoder();
    private final OrderRemovedDecoder removed = new OrderRemovedDecoder();

    private long lastOrderId;

    @Override
    public void onEvent(final DirectBuffer buffer, final int offset, final int length) {
      header.wrap(buffer, offset);
      final int body = offset + header.encodedLength();
      final int block = header.blockLength();
      final int version = header.version();

      switch (header.templateId()) {
        case OrderAcceptedDecoder.TEMPLATE_ID -> {
          accepted.wrap(buffer, body, block, version);
          lastOrderId = accepted.orderId();
          latest.add(
              "ACCEPTED order=%d client=%d"
                  .formatted(accepted.orderId(), accepted.clientOrderId()));
        }
        case OrderRejectedDecoder.TEMPLATE_ID -> {
          rejected.wrap(buffer, body, block, version);
          latest.add(
              "REJECTED client=%d reason=%s"
                  .formatted(rejected.clientOrderId(), rejected.reason()));
        }
        case OrderRestedDecoder.TEMPLATE_ID -> {
          rested.wrap(buffer, body, block, version);
          latest.add(
              "RESTED order=%d side=%s price=%d qty=%d"
                  .formatted(rested.orderId(), rested.side(), rested.price(), rested.quantity()));
        }
        case OrderExecutedDecoder.TEMPLATE_ID -> {
          executed.wrap(buffer, body, block, version);
          latest.add(
              "EXECUTED exec=%d aggressor=%d resting=%d price=%d qty=%d"
                  .formatted(
                      executed.executionId(),
                      executed.aggressorOrderId(),
                      executed.restingOrderId(),
                      executed.price(),
                      executed.quantity()));
        }
        case OrderReducedDecoder.TEMPLATE_ID -> {
          reduced.wrap(buffer, body, block, version);
          latest.add("REDUCED order=%d qty=%d".formatted(reduced.orderId(), reduced.quantity()));
        }
        case OrderRemovedDecoder.TEMPLATE_ID -> {
          removed.wrap(buffer, body, block, version);
          latest.add(
              "REMOVED order=%d qty=%d reason=%s"
                  .formatted(removed.orderId(), removed.quantity(), removed.reason()));
        }
        default -> throw new IllegalStateException("unknown event " + header.templateId());
      }
    }
  }
}

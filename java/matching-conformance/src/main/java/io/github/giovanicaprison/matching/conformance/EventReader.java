package io.github.giovanicaprison.matching.conformance;

import io.github.giovanicaprison.matching.protocol.AuctionIndicativeDecoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import io.github.giovanicaprison.matching.protocol.OrderAcceptedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderExecutedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderReducedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRejectedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRemovedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRestedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderTriggeredDecoder;
import io.github.giovanicaprison.matching.protocol.SessionStateChangedDecoder;
import org.agrona.DirectBuffer;

/**
 * Turns an event into the line a fixture writes for it.
 *
 * <p>Every event also goes into the book a consumer would build from it, since this is the one
 * place that decodes each event exactly once. Rendering and rebuilding from the same decode keeps
 * the two from disagreeing about what an event said.
 *
 * <p>Only what a fixture asserts is rendered. Sequence numbers are left out because they are a
 * property of the stream rather than of the event: asserting them would make every fixture depend
 * on how many events happen to precede the one being checked.
 */
final class EventReader {

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final OrderAcceptedDecoder accepted = new OrderAcceptedDecoder();
  private final OrderRejectedDecoder rejected = new OrderRejectedDecoder();
  private final OrderRestedDecoder rested = new OrderRestedDecoder();
  private final OrderExecutedDecoder executed = new OrderExecutedDecoder();
  private final OrderReducedDecoder reduced = new OrderReducedDecoder();
  private final OrderRemovedDecoder removed = new OrderRemovedDecoder();
  private final OrderTriggeredDecoder triggered = new OrderTriggeredDecoder();
  private final SessionStateChangedDecoder state = new SessionStateChangedDecoder();
  private final AuctionIndicativeDecoder indicative = new AuctionIndicativeDecoder();

  private final References references;
  private final ConsumerBook rebuilt;

  EventReader(final References references, final ConsumerBook rebuilt) {
    this.references = references;
    this.rebuilt = rebuilt;
  }

  String read(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    final int body = offset + header.encodedLength();
    return switch (header.templateId()) {
      case OrderAcceptedDecoder.TEMPLATE_ID -> accepted(buffer, body);
      case OrderRejectedDecoder.TEMPLATE_ID -> rejected(buffer, body);
      case OrderRestedDecoder.TEMPLATE_ID -> rested(buffer, body);
      case OrderExecutedDecoder.TEMPLATE_ID -> executed(buffer, body);
      case OrderReducedDecoder.TEMPLATE_ID -> reduced(buffer, body);
      case OrderRemovedDecoder.TEMPLATE_ID -> removed(buffer, body);
      case OrderTriggeredDecoder.TEMPLATE_ID -> triggered(buffer, body);
      case SessionStateChangedDecoder.TEMPLATE_ID -> state(buffer, body);
      case AuctionIndicativeDecoder.TEMPLATE_ID -> indicative(buffer, body);
      default ->
          throw new IllegalStateException(
              "template " + header.templateId() + " is not an event this protocol defines");
    };
  }

  private String accepted(final DirectBuffer buffer, final int offset) {
    accepted.wrap(buffer, offset, header.blockLength(), header.version());
    references.bind((int) accepted.clientOrderId(), accepted.orderId());
    rebuilt.accepted(accepted.orderId());
    return Verb.ACCEPTED + " " + references.render(accepted.orderId());
  }

  private String rejected(final DirectBuffer buffer, final int offset) {
    rejected.wrap(buffer, offset, header.blockLength(), header.version());
    return Verb.REJECTED + " #" + rejected.clientOrderId() + " " + rejected.reason();
  }

  private String rested(final DirectBuffer buffer, final int offset) {
    rested.wrap(buffer, offset, header.blockLength(), header.version());
    rebuilt.rested(rested.orderId(), rested.side(), rested.price(), rested.quantity());
    return Verb.RESTED
        + " "
        + references.render(rested.orderId())
        + " "
        + rested.side()
        + " "
        + rested.price()
        + " "
        + rested.quantity();
  }

  private String executed(final DirectBuffer buffer, final int offset) {
    executed.wrap(buffer, offset, header.blockLength(), header.version());
    rebuilt.executed(
        executed.aggressorOrderId(),
        executed.restingOrderId(),
        executed.price(),
        executed.quantity());
    return Verb.EXECUTED
        + " "
        + references.renderExecution(executed.executionId())
        + " aggressor="
        + references.render(executed.aggressorOrderId())
        + " resting="
        + references.render(executed.restingOrderId())
        + " "
        + executed.price()
        + " "
        + executed.quantity();
  }

  private String reduced(final DirectBuffer buffer, final int offset) {
    reduced.wrap(buffer, offset, header.blockLength(), header.version());
    rebuilt.reduced(reduced.orderId(), reduced.quantity());
    return Verb.REDUCED + " " + references.render(reduced.orderId()) + " " + reduced.quantity();
  }

  private String removed(final DirectBuffer buffer, final int offset) {
    removed.wrap(buffer, offset, header.blockLength(), header.version());
    rebuilt.removed(removed.orderId(), removed.quantity());
    return Verb.REMOVED
        + " "
        + references.render(removed.orderId())
        + " "
        + removed.quantity()
        + " "
        + removed.reason();
  }

  private String triggered(final DirectBuffer buffer, final int offset) {
    triggered.wrap(buffer, offset, header.blockLength(), header.version());
    return Verb.TRIGGERED + " " + references.render(triggered.orderId());
  }

  private String state(final DirectBuffer buffer, final int offset) {
    state.wrap(buffer, offset, header.blockLength(), header.version());
    return Verb.STATE + " " + state.state();
  }

  private String indicative(final DirectBuffer buffer, final int offset) {
    indicative.wrap(buffer, offset, header.blockLength(), header.version());
    return Verb.INDICATIVE + " " + indicative.price() + " " + indicative.quantity();
  }
}

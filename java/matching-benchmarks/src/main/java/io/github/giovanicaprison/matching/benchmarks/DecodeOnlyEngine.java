package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import io.github.giovanicaprison.matching.protocol.CancelOrderDecoder;
import io.github.giovanicaprison.matching.protocol.InstrumentDefinitionDecoder;
import io.github.giovanicaprison.matching.protocol.MassCancelDecoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import io.github.giovanicaprison.matching.protocol.NewOrderDecoder;
import io.github.giovanicaprison.matching.protocol.ReplaceOrderDecoder;
import io.github.giovanicaprison.matching.protocol.SessionStateChangeDecoder;
import org.agrona.DirectBuffer;

/**
 * The decode arm: every field of every command read, and nothing done with any of it.
 *
 * <p>Decode sits inside every engine's measurement on purpose, because it is part of what an
 * implementation is. This arm is what lets it be attributed separately (NFR-4.6): the same log
 * through this pseudo-engine measures decode alone, and an engine's number minus this one is
 * matching. Without it the difference between two books can be swamped by the difference between
 * two decoders and nobody would know.
 *
 * <p>Every field folds into a running sum a data dependency keeps live, so the reads cannot be
 * dead-code eliminated into measuring nothing. No event is published: an output would put encode
 * cost inside a number that exists to isolate decode.
 */
// Counting by ordinal into a flat array is deliberate: this engine exists to price decode
// alone, so nothing heavier than an index stands between it and the tally.
@SuppressWarnings("EnumOrdinal")
public final class DecodeOnlyEngine implements MatchingEngine {

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final InstrumentDefinitionDecoder definition = new InstrumentDefinitionDecoder();
  private final NewOrderDecoder newOrder = new NewOrderDecoder();
  private final CancelOrderDecoder cancel = new CancelOrderDecoder();
  private final ReplaceOrderDecoder replace = new ReplaceOrderDecoder();
  private final MassCancelDecoder massCancel = new MassCancelDecoder();
  private final SessionStateChangeDecoder sessionState = new SessionStateChangeDecoder();

  private long consumed;

  @Override
  public void onCommand(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    final int body = offset + MessageHeaderDecoder.ENCODED_LENGTH;
    final int block = header.blockLength();
    final int version = header.version();
    switch (header.templateId()) {
      case InstrumentDefinitionDecoder.TEMPLATE_ID -> {
        definition.wrap(buffer, body, block, version);
        consumed +=
            definition.tickSize()
                + definition.lotSize()
                + definition.minPrice()
                + definition.maxPrice()
                + definition.priceScale()
                + definition.bandWidth()
                + definition.openingReference()
                + definition.allocation().ordinal();
      }
      case NewOrderDecoder.TEMPLATE_ID -> {
        newOrder.wrap(buffer, body, block, version);
        consumed +=
            newOrder.clientOrderId()
                + newOrder.participantId()
                + newOrder.side().ordinal()
                + newOrder.pricing().ordinal()
                + newOrder.timeInForce().ordinal()
                + (newOrder.flags().postOnly() ? 1 : 0)
                + newOrder.price()
                + newOrder.quantity()
                + newOrder.minQuantity()
                + newOrder.displayQuantity()
                + newOrder.triggerPrice()
                + newOrder.smpId();
      }
      case CancelOrderDecoder.TEMPLATE_ID -> {
        cancel.wrap(buffer, body, block, version);
        consumed += cancel.clientOrderId() + cancel.participantId();
      }
      case ReplaceOrderDecoder.TEMPLATE_ID -> {
        replace.wrap(buffer, body, block, version);
        consumed +=
            replace.clientOrderId()
                + replace.participantId()
                + replace.quantity()
                + replace.price();
      }
      case MassCancelDecoder.TEMPLATE_ID -> {
        massCancel.wrap(buffer, body, block, version);
        consumed += massCancel.clientOrderId() + massCancel.participantId();
      }
      case SessionStateChangeDecoder.TEMPLATE_ID -> {
        sessionState.wrap(buffer, body, block, version);
        consumed += sessionState.state().ordinal();
      }
      default ->
          throw new IllegalArgumentException(
              "template " + header.templateId() + " is not a command (P-14)");
    }
  }

  /** The sum the decodes fold into, read by a test so the folding is provably not eliminable. */
  long consumed() {
    return consumed;
  }

  /** Builds the decode arm, named on a command line the way any engine is. */
  public static final class Factory implements MatchingEngineFactory {

    @Override
    public MatchingEngine create(final EventPublisher events) {
      return new DecodeOnlyEngine();
    }
  }
}

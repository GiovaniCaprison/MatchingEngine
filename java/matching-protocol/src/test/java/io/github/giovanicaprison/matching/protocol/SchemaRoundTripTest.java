package io.github.giovanicaprison.matching.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The codecs are generated, so these do not test SBE. They test the schema: a wrong primitive type,
 * a field in the wrong place or an enum that cannot hold its own values shows up here and nowhere
 * else until an implementation reads a field and gets a plausible wrong number.
 *
 * <p>One command and one event is enough. Every other message is the same shape.
 */
class SchemaRoundTripTest {

  private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);

  @Test
  @DisplayName("a new order survives a round trip with every field intact")
  void new_order_round_trips() {
    final NewOrderEncoder encoder = new NewOrderEncoder();
    encoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder());
    encoder.frame().instrumentId(7).sequence(9_001L);
    encoder
        .clientOrderId(1234L)
        .participantId(42)
        .side(Side.SELL)
        .pricing(PricingInstruction.LIMIT)
        .timeInForce(TimeInForce.IMMEDIATE_OR_CANCEL)
        .price(100_250L)
        .quantity(500L)
        .minQuantity(100L)
        .displayQuantity(50L)
        .triggerPrice(100_500L)
        .smpId(77L);
    encoder.flags().postOnly(true);

    final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buffer, 0);
    assertThat(header.templateId()).isEqualTo(NewOrderDecoder.TEMPLATE_ID);
    assertThat(header.schemaId()).isEqualTo(NewOrderDecoder.SCHEMA_ID);

    final NewOrderDecoder decoder = new NewOrderDecoder();
    decoder.wrap(buffer, header.encodedLength(), header.blockLength(), header.version());

    assertThat(decoder.frame().instrumentId()).isEqualTo(7L);
    assertThat(decoder.frame().sequence()).isEqualTo(9_001L);
    assertThat(decoder.clientOrderId()).isEqualTo(1234L);
    assertThat(decoder.participantId()).isEqualTo(42L);
    assertThat(decoder.side()).isEqualTo(Side.SELL);
    assertThat(decoder.pricing()).isEqualTo(PricingInstruction.LIMIT);
    assertThat(decoder.timeInForce()).isEqualTo(TimeInForce.IMMEDIATE_OR_CANCEL);
    assertThat(decoder.flags().postOnly()).isTrue();
    assertThat(decoder.price()).isEqualTo(100_250L);
    assertThat(decoder.quantity()).isEqualTo(500L);
    assertThat(decoder.minQuantity()).isEqualTo(100L);
    assertThat(decoder.displayQuantity()).isEqualTo(50L);
    assertThat(decoder.triggerPrice()).isEqualTo(100_500L);
    assertThat(decoder.smpId()).isEqualTo(77L);
  }

  @Test
  @DisplayName("an execution carries its own sequence and nothing about its cause")
  void execution_round_trips() {
    final OrderExecutedEncoder encoder = new OrderExecutedEncoder();
    encoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder());
    encoder.frame().instrumentId(7).sequence(500L);
    encoder.executionId(88L).aggressorOrderId(2L).restingOrderId(1L).price(100_250L).quantity(300L);

    final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buffer, 0);
    final OrderExecutedDecoder decoder = new OrderExecutedDecoder();
    decoder.wrap(buffer, header.encodedLength(), header.blockLength(), header.version());

    assertThat(decoder.frame().instrumentId()).isEqualTo(7L);
    assertThat(decoder.frame().sequence()).isEqualTo(500L);
    assertThat(decoder.executionId()).isEqualTo(88L);
    assertThat(decoder.aggressorOrderId()).isEqualTo(2L);
    assertThat(decoder.restingOrderId()).isEqualTo(1L);
    assertThat(decoder.price()).isEqualTo(100_250L);
    assertThat(decoder.quantity()).isEqualTo(300L);
  }

  @Test
  @DisplayName("a price is a scaled integer wide enough not to wrap")
  void price_is_a_full_width_signed_integer() {
    // P-11: prices are scaled integers. A narrower field would silently wrap at a plausible scale,
    // and the failure would look like a matching bug rather than an encoding one.
    final NewOrderEncoder encoder = new NewOrderEncoder();
    encoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder());
    encoder.price(Long.MAX_VALUE).quantity(Long.MAX_VALUE);

    final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buffer, 0);
    final NewOrderDecoder decoder = new NewOrderDecoder();
    decoder.wrap(buffer, header.encodedLength(), header.blockLength(), header.version());

    assertThat(decoder.price()).isEqualTo(Long.MAX_VALUE);
    assertThat(decoder.quantity()).isEqualTo(Long.MAX_VALUE);
  }
}

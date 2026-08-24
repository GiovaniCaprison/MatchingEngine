// The codecs are generated, so these do not test SBE. They test that both languages read one schema
// the same way: the Java side asserts the same fields at the same offsets, and a disagreement here
// means the two implementations are not being fed the same bytes.

#include <array>
#include <catch2/catch_test_macros.hpp>
#include <cstdint>

#include "io_github_giovanicaprison_matching_protocol/MessageHeader.h"
#include "io_github_giovanicaprison_matching_protocol/NewOrder.h"
#include "io_github_giovanicaprison_matching_protocol/OrderExecuted.h"

using namespace io::github::giovanicaprison::matching::protocol;

namespace {

std::array<char, 256> buffer{};

}  // namespace

TEST_CASE("a new order survives a round trip with every field intact") {
  MessageHeader header;
  NewOrder encoder;
  encoder.wrapAndApplyHeader(buffer.data(), 0, buffer.size());
  encoder.frame().instrumentId(7).sequence(9001);
  encoder.clientOrderId(1234)
      .participantId(42)
      .side(Side::SELL)
      .pricing(PricingInstruction::LIMIT)
      .timeInForce(TimeInForce::IMMEDIATE_OR_CANCEL)
      .price(100250)
      .quantity(500)
      .minQuantity(100)
      .displayQuantity(50)
      .triggerPrice(100500)
      .smpId(77);
  encoder.flags().clear().postOnly(true);

  header.wrap(buffer.data(), 0, 0, buffer.size());
  CHECK(header.templateId() == NewOrder::sbeTemplateId());
  CHECK(header.schemaId() == NewOrder::sbeSchemaId());

  NewOrder decoder;
  decoder.wrapForDecode(buffer.data(), header.encodedLength(), header.blockLength(),
                        header.version(), buffer.size());

  CHECK(decoder.frame().instrumentId() == 7);
  CHECK(decoder.frame().sequence() == 9001);
  CHECK(decoder.clientOrderId() == 1234);
  CHECK(decoder.participantId() == 42);
  CHECK(decoder.side() == Side::SELL);
  CHECK(decoder.pricing() == PricingInstruction::LIMIT);
  CHECK(decoder.timeInForce() == TimeInForce::IMMEDIATE_OR_CANCEL);
  CHECK(decoder.flags().postOnly());
  CHECK(decoder.price() == 100250);
  CHECK(decoder.quantity() == 500);
  CHECK(decoder.minQuantity() == 100);
  CHECK(decoder.displayQuantity() == 50);
  CHECK(decoder.triggerPrice() == 100500);
  CHECK(decoder.smpId() == 77);
}

TEST_CASE("an execution carries its own sequence and nothing about its cause") {
  MessageHeader header;
  OrderExecuted encoder;
  encoder.wrapAndApplyHeader(buffer.data(), 0, buffer.size());
  encoder.frame().instrumentId(7).sequence(500);
  encoder.executionId(88).aggressorOrderId(2).restingOrderId(1).price(100250).quantity(300);

  header.wrap(buffer.data(), 0, 0, buffer.size());
  OrderExecuted decoder;
  decoder.wrapForDecode(buffer.data(), header.encodedLength(), header.blockLength(),
                        header.version(), buffer.size());

  CHECK(decoder.frame().instrumentId() == 7);
  CHECK(decoder.frame().sequence() == 500);
  CHECK(decoder.executionId() == 88);
  CHECK(decoder.aggressorOrderId() == 2);
  CHECK(decoder.restingOrderId() == 1);
  CHECK(decoder.price() == 100250);
  CHECK(decoder.quantity() == 300);
}

TEST_CASE("a price is a scaled integer wide enough not to wrap") {
  // P-11: prices are scaled integers. A narrower field would silently wrap at a plausible scale,
  // and the failure would look like a matching bug rather than an encoding one.
  NewOrder encoder;
  encoder.wrapAndApplyHeader(buffer.data(), 0, buffer.size());
  encoder.price(INT64_MAX).quantity(INT64_MAX);

  MessageHeader header;
  header.wrap(buffer.data(), 0, 0, buffer.size());
  NewOrder decoder;
  decoder.wrapForDecode(buffer.data(), header.encodedLength(), header.blockLength(),
                        header.version(), buffer.size());

  CHECK(decoder.price() == INT64_MAX);
  CHECK(decoder.quantity() == INT64_MAX);
}

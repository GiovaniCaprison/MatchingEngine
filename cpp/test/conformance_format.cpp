// The fixture format, read by its second reader. The whole corpus is parsed and every command
// encoded, which is what makes ambiguity surface here rather than inside an engine comparison:
// a word only the Java runner understood, an option only it tolerated, or a rendering the two
// sides spelled differently would each fail one of these before any engine exists to blame.

#include <array>
#include <catch2/catch_test_macros.hpp>
#include <string>
#include <vector>

#include "conformance/command_writer.hpp"
#include "conformance/consumer_book.hpp"
#include "conformance/corpus.hpp"
#include "conformance/event_reader.hpp"
#include "conformance/fixture_parser.hpp"
#include "conformance/references.hpp"
#include "io_github_giovanicaprison_matching_protocol/MessageHeader.h"
#include "io_github_giovanicaprison_matching_protocol/NewOrder.h"
#include "io_github_giovanicaprison_matching_protocol/OrderAccepted.h"
#include "io_github_giovanicaprison_matching_protocol/OrderExecuted.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRejected.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRemoved.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRested.h"
#include "io_github_giovanicaprison_matching_protocol/SessionStateChanged.h"

using namespace io::github::giovanicaprison::matching::conformance;
namespace protocol = io::github::giovanicaprison::matching::protocol;

namespace {

// Encodes one event the way an engine would, so the reader is tested on real bytes.
template <typename Encoder>
std::size_t event(std::array<char, 512>& buffer, const auto fields) {
  Encoder encoder;
  encoder.wrapAndApplyHeader(buffer.data(), 0, buffer.size());
  encoder.frame().instrumentId(1).sequence(1);
  fields(encoder);
  return protocol::MessageHeader::encodedLength() + encoder.encodedLength();
}

}  // namespace

TEST_CASE("every fixture in the corpus parses and every command in it encodes") {
  const std::vector<Fixture> fixtures = corpusFixtures();
  CHECK(fixtures.size() >= 77);
  for (const Fixture& fixture : fixtures) {
    CHECK_FALSE(fixture.commands().empty());
    References references;
    CommandWriter writer(references);
    for (const Command& command : fixture.commands()) {
      CHECK(writer.write(command) > protocol::MessageHeader::encodedLength());
    }
  }
}

TEST_CASE("a fixture's NEW line reaches the engine with every field where the schema puts it") {
  References references;
  CommandWriter writer(references);
  const Fixture fixture = parseFixture(
      "hand written",
      "INSTRUMENT tick=5 lot=1 scale=4 min=1 max=1000000 band=500 open=100000 alloc=PRICE_TIME\n"
      "NEW BUY LIMIT GTC 100000 50 min=10 display=20 trigger=100500 smp=7 p=2 POST_ONLY\n");
  writer.write(fixture.commands()[0]);
  const std::size_t length = writer.write(fixture.commands()[1]);

  protocol::MessageHeader header;
  header.wrap(writer.buffer(), 0, 0, length);
  CHECK(header.templateId() == protocol::NewOrder::sbeTemplateId());
  protocol::NewOrder decoder;
  decoder.wrapForDecode(writer.buffer(), header.encodedLength(), header.blockLength(),
                        header.version(), length);
  CHECK(decoder.clientOrderId() == 1);
  CHECK(decoder.participantId() == 2);
  CHECK(decoder.side() == protocol::Side::BUY);
  CHECK(decoder.pricing() == protocol::PricingInstruction::LIMIT);
  CHECK(decoder.timeInForce() == protocol::TimeInForce::GOOD_TILL_CANCEL);
  CHECK(decoder.flags().postOnly());
  CHECK(decoder.price() == 100000);
  CHECK(decoder.quantity() == 50);
  CHECK(decoder.minQuantity() == 10);
  CHECK(decoder.displayQuantity() == 20);
  CHECK(decoder.triggerPrice() == 100500);
  CHECK(decoder.smpId() == 7);
}

TEST_CASE("a word that is neither a directive nor a verb is refused, not guessed at") {
  CHECK_THROWS_AS(parseFixture("bad", "INSTRUMENT tick=5\nFROBNICATE #1\n"), MalformedFixture);
}

TEST_CASE("the instrument comes first and is defined once") {
  CHECK_THROWS_AS(parseFixture("late", "SESSION CONTINUOUS\nINSTRUMENT tick=5\n"),
                  MalformedFixture);
  CHECK_THROWS_AS(parseFixture("twice", "INSTRUMENT tick=5\nINSTRUMENT tick=5\n"),
                  MalformedFixture);
}

TEST_CASE("events are rendered exactly as a fixture writes them") {
  std::array<char, 512> buffer{};
  References references;
  ConsumerBook rebuilt;
  EventReader reader(references, rebuilt);

  std::size_t length = event<protocol::OrderAccepted>(
      buffer, [](auto& e) { e.orderId(501).clientOrderId(1).participantId(1); });
  CHECK(reader.read(buffer.data(), 0, length) == "ACCEPTED #1");

  length = event<protocol::OrderAccepted>(
      buffer, [](auto& e) { e.orderId(502).clientOrderId(2).participantId(1); });
  CHECK(reader.read(buffer.data(), 0, length) == "ACCEPTED #2");

  length = event<protocol::OrderRested>(
      buffer, [](auto& e) { e.orderId(501).side(protocol::Side::BUY).price(100000).quantity(50); });
  CHECK(reader.read(buffer.data(), 0, length) == "RESTED #1 BUY 100000 50");

  length = event<protocol::OrderExecuted>(buffer, [](auto& e) {
    e.executionId(9001).aggressorOrderId(502).restingOrderId(501).price(100000).quantity(30);
  });
  CHECK(reader.read(buffer.data(), 0, length) == "EXECUTED @1 aggressor=#2 resting=#1 100000 30");

  length = event<protocol::OrderRemoved>(buffer, [](auto& e) {
    e.orderId(501).quantity(20).reason(protocol::RemoveReason::CANCELLED);
  });
  CHECK(reader.read(buffer.data(), 0, length) == "REMOVED #1 20 CANCELLED");

  length = event<protocol::OrderRejected>(buffer, [](auto& e) {
    e.clientOrderId(3).participantId(1).reason(protocol::RejectReason::TICK_VIOLATION);
  });
  CHECK(reader.read(buffer.data(), 0, length) == "REJECTED #3 TICK_VIOLATION");

  length = event<protocol::SessionStateChanged>(
      buffer, [](auto& e) { e.state(protocol::SessionState::CONTINUOUS); });
  CHECK(reader.read(buffer.data(), 0, length) == "STATE CONTINUOUS");

  CHECK(rebuilt.problems().empty());
  CHECK(rebuilt.entries().empty());
}

TEST_CASE("a stream a consumer cannot follow is reported rather than absorbed") {
  std::array<char, 512> buffer{};
  References references;
  ConsumerBook rebuilt;
  EventReader reader(references, rebuilt);

  // Rested without having been accepted, then executed at a price worse than its own limit.
  std::size_t length = event<protocol::OrderRested>(
      buffer, [](auto& e) { e.orderId(700).side(protocol::Side::BUY).price(100000).quantity(50); });
  reader.read(buffer.data(), 0, length);
  CHECK(rebuilt.problems().size() == 1);

  length = event<protocol::OrderAccepted>(
      buffer, [](auto& e) { e.orderId(700).clientOrderId(1).participantId(1); });
  reader.read(buffer.data(), 0, length);
  length = event<protocol::OrderRested>(
      buffer, [](auto& e) { e.orderId(700).side(protocol::Side::BUY).price(100000).quantity(50); });
  reader.read(buffer.data(), 0, length);
  length = event<protocol::OrderExecuted>(buffer, [](auto& e) {
    e.executionId(1).aggressorOrderId(999).restingOrderId(700).price(100005).quantity(10);
  });
  reader.read(buffer.data(), 0, length);

  CHECK(rebuilt.problems().size() == 2);
  CHECK(rebuilt.problems().back() == "700 executed at 100005 having asked for 100000");
}

TEST_CASE("an order executed in full leaves the consumer's book with no removal") {
  std::array<char, 512> buffer{};
  References references;
  ConsumerBook rebuilt;
  EventReader reader(references, rebuilt);

  std::size_t length = event<protocol::OrderAccepted>(
      buffer, [](auto& e) { e.orderId(800).clientOrderId(1).participantId(1); });
  reader.read(buffer.data(), 0, length);
  length = event<protocol::OrderRested>(buffer, [](auto& e) {
    e.orderId(800).side(protocol::Side::SELL).price(100000).quantity(50);
  });
  reader.read(buffer.data(), 0, length);
  CHECK(rebuilt.entries().size() == 1);

  length = event<protocol::OrderExecuted>(buffer, [](auto& e) {
    e.executionId(1).aggressorOrderId(999).restingOrderId(800).price(100000).quantity(50);
  });
  reader.read(buffer.data(), 0, length);

  CHECK(rebuilt.entries().empty());
  CHECK(rebuilt.problems().empty());

  // And a removal arriving anyway is the failure the book exists to catch.
  length = event<protocol::OrderRemoved>(buffer, [](auto& e) {
    e.orderId(800).quantity(50).reason(protocol::RemoveReason::CANCELLED);
  });
  reader.read(buffer.data(), 0, length);
  CHECK(rebuilt.problems().size() == 1);
}

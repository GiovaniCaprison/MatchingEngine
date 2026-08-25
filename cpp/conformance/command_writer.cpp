#include "conformance/command_writer.hpp"

#include <stdexcept>

#include "io_github_giovanicaprison_matching_protocol/CancelOrder.h"
#include "io_github_giovanicaprison_matching_protocol/InstrumentDefinition.h"
#include "io_github_giovanicaprison_matching_protocol/MassCancel.h"
#include "io_github_giovanicaprison_matching_protocol/MessageHeader.h"
#include "io_github_giovanicaprison_matching_protocol/NewOrder.h"
#include "io_github_giovanicaprison_matching_protocol/ReplaceOrder.h"
#include "io_github_giovanicaprison_matching_protocol/SessionStateChange.h"

namespace io::github::giovanicaprison::matching::conformance {

namespace {

using protocol::AllocationAlgorithm;
using protocol::MessageHeader;
using protocol::PricingInstruction;
using protocol::SessionState;
using protocol::Side;
using protocol::TimeInForce;

constexpr std::uint32_t INSTRUMENT_ID = 1;
constexpr int DEFAULT_PARTICIPANT = 1;
constexpr char ABSENT[] = "-";

std::unordered_map<std::string, std::string> options(const std::vector<std::string>& words,
                                                     const std::size_t from) {
  std::unordered_map<std::string, std::string> found;
  for (std::size_t at = from; at < words.size(); at++) {
    const std::size_t equals = words[at].find('=');
    if (equals != std::string::npos && equals > 0) {
      found.emplace(words[at].substr(0, equals), words[at].substr(equals + 1));
    }
  }
  return found;
}

std::int64_t number(const std::unordered_map<std::string, std::string>& options,
                    const std::string& key) {
  const auto found = options.find(key);
  if (found == options.end()) {
    throw std::runtime_error("missing " + key);
  }
  return std::stoll(found->second);
}

std::int64_t number(const std::unordered_map<std::string, std::string>& options,
                    const std::string& key, const std::int64_t fallback) {
  const auto found = options.find(key);
  return found == options.end() ? fallback : std::stoll(found->second);
}

std::string word(const std::unordered_map<std::string, std::string>& options,
                 const std::string& key) {
  const auto found = options.find(key);
  if (found == options.end()) {
    throw std::runtime_error("missing " + key);
  }
  return found->second;
}

std::int64_t price(const std::string& word) { return word == ABSENT ? 0 : std::stoll(word); }

int reference(const std::string& word) {
  if (word.empty() || word.front() != '#') {
    throw std::runtime_error("expected an order reference, got " + word);
  }
  return std::stoi(word.substr(1));
}

// Fixtures write the time in force short. Four names, so they are written out here.
TimeInForce::Value timeInForce(const std::string& word) {
  if (word == "GTC") return TimeInForce::GOOD_TILL_CANCEL;
  if (word == "DAY") return TimeInForce::DAY;
  if (word == "IOC") return TimeInForce::IMMEDIATE_OR_CANCEL;
  if (word == "FOK") return TimeInForce::FILL_OR_KILL;
  throw std::runtime_error("unknown time in force " + word);
}

Side::Value sideOf(const std::string& word) {
  for (const Side::Value value : {Side::BUY, Side::SELL}) {
    if (word == Side::c_str(value)) {
      return value;
    }
  }
  throw std::runtime_error("unknown side " + word);
}

PricingInstruction::Value pricingOf(const std::string& word) {
  for (const PricingInstruction::Value value :
       {PricingInstruction::LIMIT, PricingInstruction::MARKET}) {
    if (word == PricingInstruction::c_str(value)) {
      return value;
    }
  }
  throw std::runtime_error("unknown pricing instruction " + word);
}

SessionState::Value sessionStateOf(const std::string& word) {
  for (const SessionState::Value value :
       {SessionState::PRE_OPEN, SessionState::OPENING_AUCTION, SessionState::CONTINUOUS,
        SessionState::CLOSING_AUCTION, SessionState::HALTED, SessionState::CLOSED}) {
    if (word == SessionState::c_str(value)) {
      return value;
    }
  }
  throw std::runtime_error("unknown session state " + word);
}

AllocationAlgorithm::Value allocationOf(const std::string& word) {
  for (const AllocationAlgorithm::Value value :
       {AllocationAlgorithm::PRICE_TIME, AllocationAlgorithm::PRO_RATA}) {
    if (word == AllocationAlgorithm::c_str(value)) {
      return value;
    }
  }
  throw std::runtime_error("unknown allocation algorithm " + word);
}

bool contains(const std::vector<std::string>& words, const char* wanted) {
  for (const std::string& candidate : words) {
    if (candidate == wanted) {
      return true;
    }
  }
  return false;
}

std::size_t length(const std::uint64_t encodedLength) {
  return MessageHeader::encodedLength() + encodedLength;
}

}  // namespace

std::size_t CommandWriter::write(const Command& command) {
  sequence_++;
  switch (command.directive) {
    case Directive::INSTRUMENT:
      return instrument(command.arguments);
    case Directive::SESSION:
      return session(command.arguments);
    case Directive::NEW:
      return newOrder(command.arguments);
    case Directive::CANCEL:
      return cancel(command.arguments);
    case Directive::REPLACE:
      return replace(command.arguments);
    case Directive::MASSCANCEL:
      return massCancel(command.arguments);
  }
  throw std::runtime_error("unreachable");
}

std::size_t CommandWriter::instrument(const std::vector<std::string>& arguments) {
  const Options given = options(arguments, 0);
  protocol::InstrumentDefinition encoder;
  encoder.wrapAndApplyHeader(buffer_.data(), 0, buffer_.size());
  encoder.frame().instrumentId(INSTRUMENT_ID).sequence(sequence_);
  encoder.tickSize(number(given, "tick"))
      .lotSize(number(given, "lot"))
      .minPrice(number(given, "min"))
      .maxPrice(number(given, "max"))
      .priceScale(static_cast<std::uint8_t>(number(given, "scale")))
      .bandWidth(number(given, "band"))
      .openingReference(number(given, "open"))
      .allocation(allocationOf(word(given, "alloc")));
  return length(encoder.encodedLength());
}

std::size_t CommandWriter::session(const std::vector<std::string>& arguments) {
  protocol::SessionStateChange encoder;
  encoder.wrapAndApplyHeader(buffer_.data(), 0, buffer_.size());
  encoder.frame().instrumentId(INSTRUMENT_ID).sequence(sequence_);
  encoder.state(sessionStateOf(arguments.front()));
  return length(encoder.encodedLength());
}

std::size_t CommandWriter::newOrder(const std::vector<std::string>& arguments) {
  const Options given = options(arguments, 5);
  const int reference = ++orders_;
  const int participant = static_cast<int>(number(given, "p", DEFAULT_PARTICIPANT));
  references_.declare(reference, participant);

  protocol::NewOrder encoder;
  encoder.wrapAndApplyHeader(buffer_.data(), 0, buffer_.size());
  encoder.frame().instrumentId(INSTRUMENT_ID).sequence(sequence_);
  encoder.clientOrderId(reference)
      .participantId(participant)
      .side(sideOf(arguments[0]))
      .pricing(pricingOf(arguments[1]))
      .timeInForce(timeInForce(arguments[2]))
      .price(price(arguments[3]))
      .quantity(std::stoll(arguments[4]))
      .minQuantity(number(given, "min", 0))
      .displayQuantity(number(given, "display", 0))
      .triggerPrice(number(given, "trigger", 0))
      .smpId(number(given, "smp", 0));
  encoder.flags().clear().postOnly(contains(arguments, "POST_ONLY"));
  return length(encoder.encodedLength());
}

std::size_t CommandWriter::cancel(const std::vector<std::string>& arguments) {
  const int named = reference(arguments.front());
  const Options given = options(arguments, 1);
  protocol::CancelOrder encoder;
  encoder.wrapAndApplyHeader(buffer_.data(), 0, buffer_.size());
  encoder.frame().instrumentId(INSTRUMENT_ID).sequence(sequence_);
  encoder.clientOrderId(named).participantId(
      static_cast<std::uint32_t>(number(given, "p", references_.participant(named))));
  return length(encoder.encodedLength());
}

std::size_t CommandWriter::replace(const std::vector<std::string>& arguments) {
  const int named = reference(arguments.front());
  protocol::ReplaceOrder encoder;
  encoder.wrapAndApplyHeader(buffer_.data(), 0, buffer_.size());
  encoder.frame().instrumentId(INSTRUMENT_ID).sequence(sequence_);
  encoder.clientOrderId(named)
      .participantId(references_.participant(named))
      .quantity(std::stoll(arguments[1]))
      .price(price(arguments[2]));
  return length(encoder.encodedLength());
}

std::size_t CommandWriter::massCancel(const std::vector<std::string>& arguments) {
  const Options given = options(arguments, 0);
  protocol::MassCancel encoder;
  encoder.wrapAndApplyHeader(buffer_.data(), 0, buffer_.size());
  encoder.frame().instrumentId(INSTRUMENT_ID).sequence(sequence_);
  encoder.clientOrderId(0).participantId(static_cast<std::uint32_t>(number(given, "p")));
  return length(encoder.encodedLength());
}

}  // namespace io::github::giovanicaprison::matching::conformance

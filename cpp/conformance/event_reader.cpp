#include "conformance/event_reader.hpp"

#include <stdexcept>

#include "io_github_giovanicaprison_matching_protocol/AuctionIndicative.h"
#include "io_github_giovanicaprison_matching_protocol/MessageHeader.h"
#include "io_github_giovanicaprison_matching_protocol/OrderAccepted.h"
#include "io_github_giovanicaprison_matching_protocol/OrderExecuted.h"
#include "io_github_giovanicaprison_matching_protocol/OrderReduced.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRejected.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRemoved.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRested.h"
#include "io_github_giovanicaprison_matching_protocol/OrderTriggered.h"
#include "io_github_giovanicaprison_matching_protocol/SessionStateChanged.h"

namespace io::github::giovanicaprison::matching::conformance {

namespace {

using protocol::MessageHeader;

// One wrap for whichever event this is, mirroring the Java reader's per-template dispatch.
template <typename Decoder>
Decoder decoded(char* buffer, const std::size_t offset, const MessageHeader& header,
                const std::size_t available) {
  Decoder decoder;
  decoder.wrapForDecode(buffer, offset + MessageHeader::encodedLength(), header.blockLength(),
                        header.version(), available);
  return decoder;
}

}  // namespace

std::string EventReader::read(char* buffer, const std::size_t offset, const std::size_t length) {
  const std::size_t available = offset + length;
  MessageHeader header;
  header.wrap(buffer, offset, 0, available);
  switch (header.templateId()) {
    case protocol::OrderAccepted::sbeTemplateId(): {
      auto event = decoded<protocol::OrderAccepted>(buffer, offset, header, available);
      references_.bind(static_cast<int>(event.clientOrderId()), event.orderId());
      rebuilt_.accepted(event.orderId());
      return "ACCEPTED " + references_.render(event.orderId());
    }
    case protocol::OrderRejected::sbeTemplateId(): {
      auto event = decoded<protocol::OrderRejected>(buffer, offset, header, available);
      return "REJECTED #" + std::to_string(event.clientOrderId()) + " " +
             protocol::RejectReason::c_str(event.reason());
    }
    case protocol::OrderRested::sbeTemplateId(): {
      auto event = decoded<protocol::OrderRested>(buffer, offset, header, available);
      rebuilt_.rested(event.orderId(), event.side(), event.price(), event.quantity());
      return "RESTED " + references_.render(event.orderId()) + " " +
             protocol::Side::c_str(event.side()) + " " + std::to_string(event.price()) + " " +
             std::to_string(event.quantity());
    }
    case protocol::OrderExecuted::sbeTemplateId(): {
      auto event = decoded<protocol::OrderExecuted>(buffer, offset, header, available);
      rebuilt_.executed(event.aggressorOrderId(), event.restingOrderId(), event.price(),
                        event.quantity());
      return "EXECUTED " + references_.renderExecution(event.executionId()) +
             " aggressor=" + references_.render(event.aggressorOrderId()) +
             " resting=" + references_.render(event.restingOrderId()) + " " +
             std::to_string(event.price()) + " " + std::to_string(event.quantity());
    }
    case protocol::OrderReduced::sbeTemplateId(): {
      auto event = decoded<protocol::OrderReduced>(buffer, offset, header, available);
      rebuilt_.reduced(event.orderId(), event.quantity());
      return "REDUCED " + references_.render(event.orderId()) + " " +
             std::to_string(event.quantity());
    }
    case protocol::OrderRemoved::sbeTemplateId(): {
      auto event = decoded<protocol::OrderRemoved>(buffer, offset, header, available);
      rebuilt_.removed(event.orderId(), event.quantity());
      return "REMOVED " + references_.render(event.orderId()) + " " +
             std::to_string(event.quantity()) + " " + protocol::RemoveReason::c_str(event.reason());
    }
    case protocol::OrderTriggered::sbeTemplateId(): {
      auto event = decoded<protocol::OrderTriggered>(buffer, offset, header, available);
      return "TRIGGERED " + references_.render(event.orderId());
    }
    case protocol::SessionStateChanged::sbeTemplateId(): {
      auto event = decoded<protocol::SessionStateChanged>(buffer, offset, header, available);
      return std::string("STATE ") + protocol::SessionState::c_str(event.state());
    }
    case protocol::AuctionIndicative::sbeTemplateId(): {
      auto event = decoded<protocol::AuctionIndicative>(buffer, offset, header, available);
      return "INDICATIVE " + std::to_string(event.price()) + " " + std::to_string(event.quantity());
    }
    default:
      throw std::runtime_error("template " + std::to_string(header.templateId()) +
                               " is not an event this protocol defines");
  }
}

}  // namespace io::github::giovanicaprison::matching::conformance

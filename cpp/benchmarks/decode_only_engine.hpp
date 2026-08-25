// The decode arm in this language: every field of every command read and folded into a sum a data
// dependency keeps live, nothing published, so decode can be attributed separately from matching
// (NFR-4.6) on this side exactly as on the Java side.

#pragma once

#include <cstdint>
#include <stdexcept>
#include <string>

#include "api/matching_engine.hpp"
#include "io_github_giovanicaprison_matching_protocol/CancelOrder.h"
#include "io_github_giovanicaprison_matching_protocol/InstrumentDefinition.h"
#include "io_github_giovanicaprison_matching_protocol/MassCancel.h"
#include "io_github_giovanicaprison_matching_protocol/MessageHeader.h"
#include "io_github_giovanicaprison_matching_protocol/NewOrder.h"
#include "io_github_giovanicaprison_matching_protocol/ReplaceOrder.h"
#include "io_github_giovanicaprison_matching_protocol/SessionStateChange.h"

namespace io::github::giovanicaprison::matching::benchmarks {

class DecodeOnlyEngine final : public api::MatchingEngine {
 public:
  void onCommand(char* buffer, const std::size_t offset, const std::size_t length) override {
    namespace protocol = io::github::giovanicaprison::matching::protocol;
    protocol::MessageHeader header;
    const std::size_t end = offset + length;
    header.wrap(buffer, offset, 0, end);
    const std::size_t body = offset + protocol::MessageHeader::encodedLength();
    switch (header.templateId()) {
      case protocol::InstrumentDefinition::sbeTemplateId(): {
        protocol::InstrumentDefinition decoder;
        decoder.wrapForDecode(buffer, body, header.blockLength(), header.version(), end);
        consumed_ += decoder.tickSize() + decoder.lotSize() + decoder.minPrice() +
                     decoder.maxPrice() + decoder.priceScale() + decoder.bandWidth() +
                     decoder.openingReference() + static_cast<std::int64_t>(decoder.allocation());
        return;
      }
      case protocol::NewOrder::sbeTemplateId(): {
        protocol::NewOrder decoder;
        decoder.wrapForDecode(buffer, body, header.blockLength(), header.version(), end);
        consumed_ += static_cast<std::int64_t>(decoder.clientOrderId()) + decoder.participantId() +
                     static_cast<std::int64_t>(decoder.side()) +
                     static_cast<std::int64_t>(decoder.pricing()) +
                     static_cast<std::int64_t>(decoder.timeInForce()) +
                     (decoder.flags().postOnly() ? 1 : 0) + decoder.price() + decoder.quantity() +
                     decoder.minQuantity() + decoder.displayQuantity() + decoder.triggerPrice() +
                     static_cast<std::int64_t>(decoder.smpId());
        return;
      }
      case protocol::CancelOrder::sbeTemplateId(): {
        protocol::CancelOrder decoder;
        decoder.wrapForDecode(buffer, body, header.blockLength(), header.version(), end);
        consumed_ += static_cast<std::int64_t>(decoder.clientOrderId()) + decoder.participantId();
        return;
      }
      case protocol::ReplaceOrder::sbeTemplateId(): {
        protocol::ReplaceOrder decoder;
        decoder.wrapForDecode(buffer, body, header.blockLength(), header.version(), end);
        consumed_ += static_cast<std::int64_t>(decoder.clientOrderId()) + decoder.participantId() +
                     decoder.quantity() + decoder.price();
        return;
      }
      case protocol::MassCancel::sbeTemplateId(): {
        protocol::MassCancel decoder;
        decoder.wrapForDecode(buffer, body, header.blockLength(), header.version(), end);
        consumed_ += static_cast<std::int64_t>(decoder.clientOrderId()) + decoder.participantId();
        return;
      }
      case protocol::SessionStateChange::sbeTemplateId(): {
        protocol::SessionStateChange decoder;
        decoder.wrapForDecode(buffer, body, header.blockLength(), header.version(), end);
        consumed_ += static_cast<std::int64_t>(decoder.state());
        return;
      }
      default:
        throw std::invalid_argument("template " + std::to_string(header.templateId()) +
                                    " is not a command (P-14)");
    }
  }

  // The sum the decodes fold into, read by a test so the folding is provably not eliminable.
  std::int64_t consumed() const { return consumed_; }

 private:
  std::int64_t consumed_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::benchmarks

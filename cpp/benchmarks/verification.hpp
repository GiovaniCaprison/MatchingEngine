// What an engine actually produced, in a form two runs and two languages compare by. The digest is
// FNV-1a 64, written out rather than taken from a library so both sides compute the same number:
// identical output means identical digests here and in the Java runner's verification.json.

#pragma once

#include <cstdint>
#include <fstream>
#include <map>
#include <string>

#include "benchmarks/json.hpp"
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

namespace io::github::giovanicaprison::matching::benchmarks {

class VerificationRecord {
 public:
  static constexpr std::uint64_t FNV_OFFSET = 0xcbf29ce484222325ULL;
  static constexpr std::uint64_t FNV_PRIME = 0x100000001b3ULL;

  static std::uint64_t hash(const std::uint64_t seed, const char* buffer,
                            const std::size_t length) {
    std::uint64_t digest = seed;
    for (std::size_t at = 0; at < length; at++) {
      digest = (digest ^ static_cast<unsigned char>(buffer[at])) * FNV_PRIME;
    }
    return digest;
  }

  // One event, header included. Bytes are hashed exactly as the engine wrote them.
  void record(char* buffer, const std::size_t length) {
    namespace protocol = io::github::giovanicaprison::matching::protocol;
    protocol::MessageHeader header;
    header.wrap(buffer, 0, 0, length);
    counts_[header.templateId()]++;
    reason(buffer, header, length);
    events_++;
    bytes_ += length;
    digest_ = hash(digest_, buffer, length);
  }

  std::uint64_t events() const { return events_; }
  std::uint64_t digest() const { return digest_; }

  std::map<std::string, std::uint64_t> countsByName() const {
    std::map<std::string, std::uint64_t> named;
    for (const auto& [templateId, count] : counts_) {
      named[nameOf(templateId)] = count;
    }
    return named;
  }

  std::string toJson() const {
    Json json;
    json.object().field("events", events_).field("bytes", bytes_);
    json.field("digest", hex(digest_)).object("counts");
    for (const auto& [templateId, count] : counts_) {
      json.field(nameOf(templateId), count);
    }
    json.end().object("reasons");
    for (const auto& [name, count] : reasons_) {
      json.field(name, count);
    }
    json.end().end();
    return json.done();
  }

  void writeTo(const std::string& file) const {
    std::ofstream out(file);
    out << toJson();
  }

 private:
  static std::string hex(const std::uint64_t value) {
    static const char* digits = "0123456789abcdef";
    std::string out;
    for (int shift = 60; shift >= 0; shift -= 4) {
      const char digit = digits[(value >> shift) & 0xF];
      if (!out.empty() || digit != '0' || shift == 0) {
        out += digit;
      }
    }
    return out;
  }

  static std::string nameOf(const std::uint16_t templateId) {
    namespace protocol = io::github::giovanicaprison::matching::protocol;
    switch (templateId) {
      case protocol::OrderAccepted::sbeTemplateId():
        return "OrderAccepted";
      case protocol::OrderRejected::sbeTemplateId():
        return "OrderRejected";
      case protocol::OrderRested::sbeTemplateId():
        return "OrderRested";
      case protocol::OrderExecuted::sbeTemplateId():
        return "OrderExecuted";
      case protocol::OrderReduced::sbeTemplateId():
        return "OrderReduced";
      case protocol::OrderRemoved::sbeTemplateId():
        return "OrderRemoved";
      case protocol::OrderTriggered::sbeTemplateId():
        return "OrderTriggered";
      case protocol::SessionStateChanged::sbeTemplateId():
        return "SessionStateChanged";
      case protocol::AuctionIndicative::sbeTemplateId():
        return "AuctionIndicative";
      default:
        return "template " + std::to_string(templateId);
    }
  }

  void reason(char* buffer, io::github::giovanicaprison::matching::protocol::MessageHeader& header,
              const std::size_t length) {
    namespace protocol = io::github::giovanicaprison::matching::protocol;
    const std::size_t body = protocol::MessageHeader::encodedLength();
    if (header.templateId() == protocol::OrderRejected::sbeTemplateId()) {
      protocol::OrderRejected event;
      event.wrapForDecode(buffer, body, header.blockLength(), header.version(), length);
      reasons_[std::string("rejected ") + protocol::RejectReason::c_str(event.reason())]++;
    } else if (header.templateId() == protocol::OrderRemoved::sbeTemplateId()) {
      protocol::OrderRemoved event;
      event.wrapForDecode(buffer, body, header.blockLength(), header.version(), length);
      reasons_[std::string("removed ") + protocol::RemoveReason::c_str(event.reason())]++;
    }
  }

  std::map<std::uint16_t, std::uint64_t> counts_;
  std::map<std::string, std::uint64_t> reasons_;
  std::uint64_t events_ = 0;
  std::uint64_t bytes_ = 0;
  std::uint64_t digest_ = FNV_OFFSET;
};

}  // namespace io::github::giovanicaprison::matching::benchmarks

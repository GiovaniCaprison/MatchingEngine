// The engine's output, encoded straight into the space the publisher hands over. Encoding is part
// of an implementation's cost and stays inside it; nothing is buffered and nothing is held back to
// see what follows. The output sequence is the engine's own, and nothing here carries the input
// sequence of the command that caused it (P-16). The Java twin writes its events as raw puts at
// offsets pinned from the generated codec; here the generated encoder inlines to the same plain
// stores, so wrapping it is already the in-place write and the two sides stay structurally
// equivalent after compilation.

#pragma once

#include <cstdint>

#include "api/event_publisher.hpp"
#include "io_github_giovanicaprison_matching_protocol/AuctionIndicative.h"
#include "io_github_giovanicaprison_matching_protocol/MessageHeader.h"
#include "io_github_giovanicaprison_matching_protocol/OrderAccepted.h"
#include "io_github_giovanicaprison_matching_protocol/OrderExecuted.h"
#include "io_github_giovanicaprison_matching_protocol/OrderReduced.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRejected.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRemoved.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRested.h"
#include "io_github_giovanicaprison_matching_protocol/OrderTriggered.h"
#include "io_github_giovanicaprison_matching_protocol/RejectReason.h"
#include "io_github_giovanicaprison_matching_protocol/RemoveReason.h"
#include "io_github_giovanicaprison_matching_protocol/SessionState.h"
#include "io_github_giovanicaprison_matching_protocol/SessionStateChanged.h"
#include "io_github_giovanicaprison_matching_protocol/Side.h"

namespace io::github::giovanicaprison::matching::flyweight {

class Feed {
 public:
  explicit Feed(api::EventPublisher& events) : events_(events) {}

  void instrument(const std::uint32_t id) { instrumentId_ = id; }

  void accepted(const std::uint64_t orderId, const std::uint64_t clientOrderId,
                const std::uint32_t participantId) {
    auto encoder = claimed<protocol::OrderAccepted>();
    encoder.orderId(orderId).clientOrderId(clientOrderId).participantId(participantId);
    events_.commit();
  }

  void rejected(const std::uint64_t clientOrderId, const std::uint32_t participantId,
                const protocol::RejectReason::Value reason) {
    auto encoder = claimed<protocol::OrderRejected>();
    encoder.clientOrderId(clientOrderId).participantId(participantId).reason(reason);
    events_.commit();
  }

  // Displayed quantity only. Hidden quantity is never reported (FR-5.2).
  void rested(const std::uint64_t orderId, const std::int32_t side, const std::int64_t price,
              const std::int64_t displayed) {
    auto encoder = claimed<protocol::OrderRested>();
    encoder.orderId(orderId)
        .side(static_cast<protocol::Side::Value>(side))
        .price(price)
        .quantity(displayed);
    events_.commit();
  }

  void executed(const std::uint64_t executionId, const std::uint64_t aggressor,
                const std::uint64_t resting, const std::int64_t price,
                const std::int64_t quantity) {
    auto encoder = claimed<protocol::OrderExecuted>();
    encoder.executionId(executionId)
        .aggressorOrderId(aggressor)
        .restingOrderId(resting)
        .price(price)
        .quantity(quantity);
    events_.commit();
  }

  void reduced(const std::uint64_t orderId, const std::int64_t displayed) {
    auto encoder = claimed<protocol::OrderReduced>();
    encoder.orderId(orderId).quantity(displayed);
    events_.commit();
  }

  void removed(const std::uint64_t orderId, const std::int64_t quantity,
               const protocol::RemoveReason::Value reason) {
    auto encoder = claimed<protocol::OrderRemoved>();
    encoder.orderId(orderId).quantity(quantity).reason(reason);
    events_.commit();
  }

  void triggered(const std::uint64_t orderId) {
    auto encoder = claimed<protocol::OrderTriggered>();
    encoder.orderId(orderId);
    events_.commit();
  }

  void stateChanged(const protocol::SessionState::Value state) {
    auto encoder = claimed<protocol::SessionStateChanged>();
    encoder.state(state);
    events_.commit();
  }

  void indicative(const std::int64_t price, const std::int64_t quantity) {
    auto encoder = claimed<protocol::AuctionIndicative>();
    encoder.price(price).quantity(quantity);
    events_.commit();
  }

 private:
  // Claims space for one event, wraps its header and frame, and hands the encoder back with the
  // event's own output sequence already on it.
  template <typename Encoder>
  Encoder claimed() {
    const std::size_t length = protocol::MessageHeader::encodedLength() + Encoder::sbeBlockLength();
    const std::size_t at = events_.claim(length);
    Encoder encoder;
    encoder.wrapAndApplyHeader(events_.buffer(), at, at + length);
    encoder.frame().instrumentId(instrumentId_).sequence(++sequence_);
    return encoder;
  }

  api::EventPublisher& events_;
  std::uint32_t instrumentId_ = 0;
  std::uint64_t sequence_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::flyweight

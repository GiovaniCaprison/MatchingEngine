// The output this engine can produce, which is less than the protocol defines. No trigger event
// and no indicative, because nothing here triggers or uncrosses; the encoders that are absent are
// absent from the object layout too, which is the same claim the whole engine makes (P-16).

#pragma once

#include <cstdint>

#include "api/event_publisher.hpp"
#include "io_github_giovanicaprison_matching_protocol/MessageHeader.h"
#include "io_github_giovanicaprison_matching_protocol/OrderAccepted.h"
#include "io_github_giovanicaprison_matching_protocol/OrderExecuted.h"
#include "io_github_giovanicaprison_matching_protocol/OrderReduced.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRejected.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRemoved.h"
#include "io_github_giovanicaprison_matching_protocol/OrderRested.h"
#include "io_github_giovanicaprison_matching_protocol/RejectReason.h"
#include "io_github_giovanicaprison_matching_protocol/RemoveReason.h"
#include "io_github_giovanicaprison_matching_protocol/SessionState.h"
#include "io_github_giovanicaprison_matching_protocol/SessionStateChanged.h"
#include "lean-naive/order.hpp"

namespace io::github::giovanicaprison::matching::lean::naive {

class Feed {
 public:
  explicit Feed(api::EventPublisher& events) : events_(events) {}

  void instrument(const std::uint32_t id) { instrumentId_ = id; }

  void accepted(const Order& order) {
    auto encoder = claimed<protocol::OrderAccepted>();
    encoder.orderId(order.id())
        .clientOrderId(order.clientOrderId())
        .participantId(order.participantId());
    events_.commit();
  }

  void rejected(const std::uint64_t clientOrderId, const std::uint32_t participantId,
                const protocol::RejectReason::Value reason) {
    auto encoder = claimed<protocol::OrderRejected>();
    encoder.clientOrderId(clientOrderId).participantId(participantId).reason(reason);
    events_.commit();
  }

  void rested(const Order& order) {
    auto encoder = claimed<protocol::OrderRested>();
    encoder.orderId(order.id()).side(order.side()).price(order.price()).quantity(order.remaining());
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

  void reduced(const Order& order) {
    auto encoder = claimed<protocol::OrderReduced>();
    encoder.orderId(order.id()).quantity(order.remaining());
    events_.commit();
  }

  void removed(const std::uint64_t orderId, const std::int64_t quantity,
               const protocol::RemoveReason::Value reason) {
    auto encoder = claimed<protocol::OrderRemoved>();
    encoder.orderId(orderId).quantity(quantity).reason(reason);
    events_.commit();
  }

  void stateChanged(const protocol::SessionState::Value state) {
    auto encoder = claimed<protocol::SessionStateChanged>();
    encoder.state(state);
    events_.commit();
  }

  void replaced(const Order& order, const std::int64_t quantityRemoved) {
    removed(order.id(), quantityRemoved, protocol::RemoveReason::REPLACED);
  }

 private:
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

}  // namespace io::github::giovanicaprison::matching::lean::naive

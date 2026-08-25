// Rung two's lean twin in this language: limit and market orders, price-time, on the pooled
// layout, at a layout matched to the Java arm. METHODOLOGY's first question is what a real venue's
// feature set costs when nobody uses it, and a runtime flag cannot answer it (P-16), so this is
// the engine where the features do not exist. On the shared remit it is byte identical to the full
// rung, and the differential holds it there.

#pragma once

#include <cstdint>
#include <vector>

#include "api/event_publisher.hpp"
#include "api/matching_engine.hpp"
#include "io_github_giovanicaprison_matching_protocol/NewOrder.h"
#include "io_github_giovanicaprison_matching_protocol/RejectReason.h"
#include "io_github_giovanicaprison_matching_protocol/SessionState.h"
#include "lean-pooled/book.hpp"
#include "lean-pooled/feed.hpp"
#include "lean-pooled/pool.hpp"

namespace io::github::giovanicaprison::matching::lean::pooled {

class LeanEngine final : public api::MatchingEngine {
 public:
  explicit LeanEngine(api::EventPublisher& events) : feed_(events), pool_(1 << 16) {
    gathered_.reserve(1024);
  }

  void onCommand(char* buffer, std::size_t offset, std::size_t length) override;

 private:
  // Aliased under different names so the protocol classes stay reachable in the bodies.
  using Refusal = protocol::RejectReason::Value;
  using State = protocol::SessionState::Value;

  void enter(protocol::NewOrder& newOrder);
  void settle(OrderPtr order);
  void match(OrderPtr taker);
  void cancel(std::uint64_t clientOrderId, std::uint32_t participantId);
  void replace(std::uint64_t clientOrderId, std::uint32_t participantId, std::int64_t quantity,
               std::int64_t price);
  void massCancel(std::uint64_t clientOrderId, std::uint32_t participantId);
  bool matching() const { return state_ == protocol::SessionState::CONTINUOUS; }

  Refusal refusalFor(protocol::NewOrder& newOrder) const;
  Refusal refusalForPrice(std::int64_t price) const;
  Refusal refusalForReplace(const Order& resting, std::int64_t quantity, std::int64_t price) const;

  Feed feed_;
  Book book_;
  Pool pool_;
  std::vector<OrderPtr> gathered_;

  std::int64_t tickSize_ = 1;
  std::int64_t lotSize_ = 1;
  std::int64_t minPrice_ = 0;
  std::int64_t maxPrice_ = 0;
  std::int64_t bandWidth_ = 0;
  State state_ = protocol::SessionState::PRE_OPEN;
  std::int64_t reference_ = 0;
  std::uint64_t nextOrderId_ = 1;
  std::uint64_t nextExecutionId_ = 1;
  std::int64_t arrival_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::lean::pooled

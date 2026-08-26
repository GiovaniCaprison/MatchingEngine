// Rung three's lean twin in this language: limit and market orders, price-time, on the flyweight
// layout, at a layout matched to the Java arm. METHODOLOGY's first question is what a real venue's
// feature set costs when nobody uses it, and a runtime flag cannot answer it (P-16), so this is
// the engine where the features do not exist: no trigger book consulted after an execution, no
// tranche arithmetic on a take, no self match comparison per candidate, no allocation choice, no
// auction, and an order slot that fits in one cache line where the full rung's needs two. On the
// shared remit it is byte identical to the full rung, and the differential holds it there.

#pragma once

#include <cstdint>
#include <optional>
#include <vector>

#include "api/event_publisher.hpp"
#include "api/matching_engine.hpp"
#include "io_github_giovanicaprison_matching_protocol/InstrumentDefinition.h"
#include "io_github_giovanicaprison_matching_protocol/NewOrder.h"
#include "io_github_giovanicaprison_matching_protocol/SessionState.h"
#include "lean-flyweight/book.hpp"
#include "lean-flyweight/feed.hpp"
#include "lean-flyweight/slab.hpp"

namespace io::github::giovanicaprison::matching::lean::flyweight {

class LeanEngine final : public api::MatchingEngine {
 public:
  explicit LeanEngine(api::EventPublisher& events) : feed_(events), slab_(1 << 16) {
    gathered_.reserve(1024);
  }

  void onCommand(char* buffer, std::size_t offset, std::size_t length) override;

 private:
  void define(protocol::InstrumentDefinition& definition);
  void enter(protocol::NewOrder& newOrder);
  void settle(std::int32_t slot, std::int32_t side);
  void match(std::int32_t taker, std::int32_t side);
  void cancel(std::uint64_t clientOrderId, std::uint32_t participantId);
  void replace(std::uint64_t clientOrderId, std::uint32_t participantId, std::int64_t quantity,
               std::int64_t price);
  void massCancel(std::uint64_t clientOrderId, std::uint32_t participantId);
  void sortByArrival(std::vector<std::int32_t>& slots) const;

  // Validation. A refusal is encoded below zero, so one verdict carries either the reason or the
  // tick, and the division that proves a price on tick (VR-2.2) is the index the ladder wants.
  std::int64_t refusalOrTick(std::int32_t pricing, std::int32_t timeInForce, std::int64_t price,
                             std::int64_t quantity) const;
  std::int64_t tickOrRefusal(std::int64_t price) const;
  std::int64_t refusalOrTickForReplace(std::int32_t resting, std::int64_t quantity,
                                       std::int64_t price) const;

  Feed feed_;
  Slab slab_;

  // The book exists once the definition arrives (FR-1.1), which is what sizes the ladder.
  std::optional<Book> book_;

  std::vector<std::int32_t> gathered_;

  std::int64_t tickSize_ = 1;
  std::int64_t lotSize_ = 1;
  std::int64_t minPrice_ = 0;
  std::int64_t maxPrice_ = 0;
  std::int64_t bandWidth_ = 0;
  std::int64_t baseTick_ = 0;
  std::int32_t state_ = protocol::SessionState::PRE_OPEN;
  std::int64_t reference_ = 0;
  std::uint64_t nextOrderId_ = 1;
  std::uint64_t nextExecutionId_ = 1;
  std::int64_t arrival_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::lean::flyweight

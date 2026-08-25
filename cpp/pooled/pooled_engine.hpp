// Rung two in this language: the indexed engine's behaviour with the allocation taken out, at a
// layout matched to the Java rung so the step between languages isolates the runtime. Orders come
// from a pool and go back when they die, levels are recycled by their tree, the working space the
// large commands need is kept between them, and the claim that the steady state allocates nothing
// is held by a probe whose allocator refuses after initialisation (NFR-4.3).

#pragma once

#include <cstdint>
#include <vector>

#include "api/event_publisher.hpp"
#include "api/matching_engine.hpp"
#include "io_github_giovanicaprison_matching_protocol/NewOrder.h"
#include "io_github_giovanicaprison_matching_protocol/RejectReason.h"
#include "io_github_giovanicaprison_matching_protocol/SessionState.h"
#include "pooled/auction.hpp"
#include "pooled/book.hpp"
#include "pooled/feed.hpp"
#include "pooled/instrument.hpp"
#include "pooled/pool.hpp"
#include "pooled/triggers.hpp"

namespace io::github::giovanicaprison::matching::pooled {

class PooledEngine final : public api::MatchingEngine {
 public:
  explicit PooledEngine(api::EventPublisher& events) : feed_(events), pool_(1 << 16) {
    pending_.reserve(1024);
    snapshot_.reserve(1024);
    gathered_.reserve(1024);
    buys_.reserve(1024);
    sells_.reserve(1024);
  }

  void onCommand(char* buffer, std::size_t offset, std::size_t length) override;

 private:
  // Aliased under different names so the protocol classes stay reachable in the bodies.
  using Refusal = protocol::RejectReason::Value;
  using State = protocol::SessionState::Value;
  using Side = protocol::Side::Value;

  void enter(protocol::NewOrder& newOrder);
  void admit(OrderPtr order);
  void settle(OrderPtr order);
  void match(OrderPtr taker);
  bool prevented(const OrderPtr taker, OrderPtr resting);
  void take(OrderPtr taker, OrderPtr resting);
  void takeExactly(OrderPtr taker, OrderPtr resting, std::int64_t quantity);
  void proRata(OrderPtr taker, std::int64_t price);
  void fireTriggers();
  void cancel(std::uint64_t clientOrderId, std::uint32_t participantId);
  void replace(std::uint64_t clientOrderId, std::uint32_t participantId, std::int64_t quantity,
               std::int64_t price);
  void massCancel(std::uint64_t clientOrderId, std::uint32_t participantId);
  void changeState(State entering);
  void uncross();
  std::int64_t cross(OrderPtr buy, OrderPtr sell, std::int64_t price, std::int64_t left);
  void reveal(OrderPtr order, bool replenishes, std::int64_t shownBefore);
  void willing(Side side, std::int64_t price, std::vector<OrderPtr>& into);
  void reportIndicative();
  bool matching() const { return state_ == protocol::SessionState::CONTINUOUS; }

  // Validation, all of it returning NULL_VALUE when there is nothing wrong.
  Refusal refusalFor(protocol::NewOrder& newOrder) const;
  Refusal refusalForPrice(std::int64_t price) const;
  Refusal refusalForTriggerPrice(std::int64_t price) const;
  Refusal refusalOnTheInstrument(std::int64_t price) const;
  Refusal refusalFromTheBook(Side side, protocol::PricingInstruction::Value pricing,
                             protocol::TimeInForce::Value timeInForce, bool postOnly,
                             std::int64_t price, std::int64_t quantity, std::int64_t minQuantity,
                             std::uint64_t smpId, std::int64_t triggerPrice) const;
  Refusal refusalForReplace(const Order& resting, std::int64_t quantity, std::int64_t price) const;

  Feed feed_;
  Book book_;
  Triggers triggers_;
  Pool pool_;
  Auction auction_;
  Book::Walk uncrossWalk_;

  // The working space the large commands keep between them (NFR-4.3). The fired queue is a vector
  // drained by index, so a cascade neither shuffles elements nor gives blocks back mid-walk.
  std::vector<OrderPtr> pending_;
  std::size_t pendingNext_ = 0;
  std::vector<OrderPtr> snapshot_;
  std::vector<OrderPtr> gathered_;
  std::vector<OrderPtr> buys_;
  std::vector<OrderPtr> sells_;

  Instrument instrument_;
  State state_ = protocol::SessionState::PRE_OPEN;
  std::int64_t reference_ = 0;
  std::int64_t lastExecuted_ = 0;
  std::uint64_t nextOrderId_ = 1;
  std::uint64_t nextExecutionId_ = 1;
  std::int64_t arrival_ = 0;
  std::int64_t indicativePrice_ = 0;
  std::int64_t indicativeQuantity_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::pooled

// Rung one in this language: the naive engine's behaviour on an indexed book, at a layout matched
// to the Java rung so the step between languages isolates the runtime. Sorted levels, intrusive
// queues and a name index answer by lookup what rung zero answered by walking, the differential
// holds the outputs byte identical, and the Java twin's invariants police the bookkeeping the
// lookups cost (NFR-3.1, NFR-3.2).

#pragma once

#include <cstdint>

#include "api/event_publisher.hpp"
#include "api/matching_engine.hpp"
#include "indexed/book.hpp"
#include "indexed/feed.hpp"
#include "indexed/instrument.hpp"
#include "indexed/triggers.hpp"
#include "io_github_giovanicaprison_matching_protocol/NewOrder.h"
#include "io_github_giovanicaprison_matching_protocol/RejectReason.h"
#include "io_github_giovanicaprison_matching_protocol/SessionState.h"

namespace io::github::giovanicaprison::matching::indexed {

class IndexedEngine final : public api::MatchingEngine {
 public:
  explicit IndexedEngine(api::EventPublisher& events) : feed_(events) {}

  void onCommand(char* buffer, std::size_t offset, std::size_t length) override;

 private:
  // Aliased under different names so the protocol classes stay reachable in the bodies.
  using Refusal = protocol::RejectReason::Value;
  using State = protocol::SessionState::Value;
  using Side = protocol::Side::Value;

  void enter(protocol::NewOrder& newOrder);
  void admit(const OrderPtr& order);
  void settle(const OrderPtr& order);
  void match(const OrderPtr& taker);
  bool prevented(const OrderPtr& taker, const OrderPtr& resting);
  void take(const OrderPtr& taker, const OrderPtr& resting);
  void takeExactly(const OrderPtr& taker, const OrderPtr& resting, std::int64_t quantity);
  void proRata(const OrderPtr& taker, std::int64_t price);
  void fireTriggers();
  void cancel(std::uint64_t clientOrderId, std::uint32_t participantId);
  void replace(std::uint64_t clientOrderId, std::uint32_t participantId, std::int64_t quantity,
               std::int64_t price);
  void massCancel(std::uint64_t clientOrderId, std::uint32_t participantId);
  void changeState(State entering);
  void uncross();
  std::int64_t cross(const OrderPtr& buy, const OrderPtr& sell, std::int64_t price,
                     std::int64_t left);
  void reveal(const OrderPtr& order, bool replenishes, std::int64_t shownBefore);
  std::vector<OrderPtr> willing(Side side, std::int64_t price) const;
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

}  // namespace io::github::giovanicaprison::matching::indexed

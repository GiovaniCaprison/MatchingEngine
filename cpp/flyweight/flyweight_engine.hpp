// Rung three in this language: the same venue as the naive engine, expressed as index arithmetic
// over a handful of flat arrays, at a layout matched word for word to the Java rung so the step
// between languages isolates the runtime. An order is an int slot in one slab of 64 bit words, a
// price level is an index into a flat ladder in a folded rank space, the best price is a bit in a
// summary word, and a command's fields are read in place from the buffer, which in this language
// is what the generated decoder already compiles to. The commands flow through the same decisions
// in the same order as every rung below, held byte identical by the cross-language differential
// (NFR-5.1), and the steady state allocates nothing, held by a probe whose allocator refuses after
// initialisation (NFR-4.3).

#pragma once

#include <cstdint>
#include <optional>
#include <vector>

#include "api/event_publisher.hpp"
#include "api/matching_engine.hpp"
#include "flyweight/auction.hpp"
#include "flyweight/book.hpp"
#include "flyweight/feed.hpp"
#include "flyweight/slab.hpp"
#include "flyweight/triggers.hpp"
#include "io_github_giovanicaprison_matching_protocol/InstrumentDefinition.h"
#include "io_github_giovanicaprison_matching_protocol/NewOrder.h"
#include "io_github_giovanicaprison_matching_protocol/SessionState.h"

namespace io::github::giovanicaprison::matching::flyweight {

class FlyweightEngine final : public api::MatchingEngine {
 public:
  explicit FlyweightEngine(api::EventPublisher& events)
      : feed_(events), slab_(1 << 16), triggers_(slab_) {
    pending_.reserve(1024);
    snapshot_.reserve(1024);
    gathered_.reserve(1024);
    buys_.reserve(1024);
    sells_.reserve(1024);
  }

  void onCommand(char* buffer, std::size_t offset, std::size_t length) override;

 private:
  void define(protocol::InstrumentDefinition& definition);
  void enter(protocol::NewOrder& newOrder);
  void admit(std::int32_t slot, std::int32_t side);
  void settle(std::int32_t slot, std::int32_t side);
  void match(std::int32_t taker, std::int32_t side);
  std::int32_t limitRankOf(std::int32_t taker, std::int32_t side) const;
  bool prevented(std::uint64_t smpId, std::int32_t resting, std::int32_t takerSide);
  void takeExactly(std::int32_t taker, std::int32_t side, std::int32_t resting,
                   std::int64_t quantity);
  void proRataTake(std::int32_t taker, std::int32_t side, std::int32_t limitRank,
                   std::int32_t tick);
  void fireTriggers();
  void cancel(std::uint64_t clientOrderId, std::uint32_t participantId);
  void replace(std::uint64_t clientOrderId, std::uint32_t participantId, std::int64_t quantity,
               std::int64_t price);
  void massCancel(std::uint64_t clientOrderId, std::uint32_t participantId);
  void changeState(std::int32_t entering);
  void uncross();
  std::int64_t cross(std::int32_t buy, std::int32_t sell, std::int64_t price, std::int64_t left);
  void reveal(std::int32_t slot, std::int32_t side, bool replenishes, std::int64_t shownBefore,
              std::int64_t quantity);
  void willing(std::int32_t side, std::int64_t price, std::vector<std::int32_t>& into);
  void reportIndicative();
  void sortByArrival(std::vector<std::int32_t>& slots) const;

  // Validation. A refusal is encoded below zero, so one verdict carries either the reason or the
  // tick, and the division that proves a price on tick (VR-2.2) is the index the ladder wants.
  std::int64_t refusalOrTick(std::int32_t side, std::int32_t pricing, std::int32_t timeInForce,
                             bool postOnly, std::int64_t price, std::int64_t quantity,
                             std::int64_t minQuantity, std::int64_t displayQuantity,
                             std::int64_t triggerPrice, std::uint64_t smpId) const;
  std::int64_t tickOrRefusal(std::int64_t price) const;
  std::int32_t refusalFromTheBook(std::int32_t side, std::int32_t pricing, std::int32_t timeInForce,
                                  bool postOnly, std::int64_t quantity, std::int64_t minQuantity,
                                  std::uint64_t smpId, std::int64_t triggerPrice,
                                  std::int32_t tick) const;
  std::int64_t refusalOrTickForReplace(std::int32_t resting, std::int64_t quantity,
                                       std::int64_t price) const;

  Feed feed_;
  Slab slab_;
  Triggers triggers_;
  Auction auction_;

  // The book exists once the definition arrives (FR-1.1), which is what sizes the ladder.
  std::optional<Book> book_;

  // The working space the large commands keep between them (NFR-4.3). The fired queue is a vector
  // drained by index, so a cascade neither shuffles elements nor gives blocks back mid-walk.
  std::vector<std::int32_t> pending_;
  std::size_t pendingNext_ = 0;
  std::vector<std::int32_t> snapshot_;
  std::vector<std::int32_t> gathered_;
  std::vector<std::int32_t> buys_;
  std::vector<std::int32_t> sells_;

  std::int64_t tickSize_ = 1;
  std::int64_t lotSize_ = 1;
  std::int64_t minPrice_ = 0;
  std::int64_t maxPrice_ = 0;
  std::int64_t bandWidth_ = 0;
  std::int64_t baseTick_ = 0;
  bool proRata_ = false;
  std::int32_t state_ = protocol::SessionState::PRE_OPEN;
  std::int64_t reference_ = 0;
  std::int64_t lastExecuted_ = 0;
  std::uint64_t nextOrderId_ = 1;
  std::uint64_t nextExecutionId_ = 1;
  std::int64_t arrival_ = 0;
  std::int64_t indicativePrice_ = 0;
  std::int64_t indicativeQuantity_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::flyweight

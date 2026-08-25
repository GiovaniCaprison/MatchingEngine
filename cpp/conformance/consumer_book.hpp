// The book a consumer builds from the event stream, and nothing else. This is the contract with
// the market data publisher above the engine, checked rather than assumed: the events are supposed
// to be sufficient to rebuild the visible book, and the way to find out is to rebuild it after
// every event and see whether it still makes sense.
//
// Order within a price is kept, because the stream says it. Hidden quantity is not here and cannot
// be: a consumer is told the displayed part only, so this is the visible book by construction.

#pragma once

#include <cstdint>
#include <string>
#include <unordered_set>
#include <vector>

#include "io_github_giovanicaprison_matching_protocol/Side.h"

namespace io::github::giovanicaprison::matching::conformance {

class ConsumerBook {
 public:
  // One resting order as the feed describes it: the engine's id, since events name it that way.
  struct Entry {
    std::uint64_t orderId = 0;
    protocol::Side::Value side = protocol::Side::BUY;
    std::int64_t price = 0;
    std::int64_t quantity = 0;

    bool operator==(const Entry&) const = default;
  };

  // Every resting order the feed has described, in the order the feed described it.
  const std::vector<Entry>& entries() const { return entries_; }

  // What the stream said that a book cannot mean. Empty is the only acceptable answer.
  const std::vector<std::string>& problems() const { return problems_; }

  void accepted(std::uint64_t orderId);
  void rested(std::uint64_t orderId, protocol::Side::Value side, std::int64_t price,
              std::int64_t quantity);
  void executed(std::uint64_t aggressorOrderId, std::uint64_t restingOrderId, std::int64_t price,
                std::int64_t quantity);
  void reduced(std::uint64_t orderId, std::int64_t quantity);
  void removed(std::uint64_t orderId, std::int64_t quantity);

 private:
  Entry* resting(std::uint64_t orderId);
  void erase(std::uint64_t orderId);
  void take(std::uint64_t orderId, std::int64_t price, std::int64_t quantity);
  void problem(std::string description);

  // A vector rather than a map, because insertion order is part of what is compared and the
  // corpus is small enough that a scan is the simple honest structure here too.
  std::vector<Entry> entries_;
  std::unordered_set<std::uint64_t> accepted_;
  std::unordered_set<std::uint64_t> everRested_;
  std::vector<std::string> problems_;
};

}  // namespace io::github::giovanicaprison::matching::conformance

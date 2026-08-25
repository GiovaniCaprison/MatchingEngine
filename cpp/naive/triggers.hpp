// The stops that have not fired. A resting stop is not liquidity and is invisible to the book: it
// is a condition evaluated against the last executed price, and on firing it becomes an ordinary
// order (FR-6.1, FR-6.3). Another list, scanned, so on this rung every execution costs a walk over
// every stop in the venue.

#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

#include "naive/order.hpp"
#include "naive/orders.hpp"

namespace io::github::giovanicaprison::matching::naive {

class Triggers {
 public:
  const std::vector<OrderPtr>& stops() const { return stops_; }

  void add(const OrderPtr& stop) { stops_.push_back(stop); }

  void remove(const OrderPtr& stop) { std::erase(stops_, stop); }

  OrderPtr named(const std::uint32_t participantId, const std::uint64_t clientOrderId) const {
    return orders::named(stops_, participantId, clientOrderId);
  }

  std::vector<OrderPtr> of(const std::uint32_t participantId) const {
    return orders::of(stops_, participantId);
  }

  // The stops the last executed price has reached, earliest first, removed as they go. A buy stop
  // is placed above the market and fires when the price rises to it; a sell stop is below and
  // fires when the price falls to it (FR-6.2).
  std::vector<OrderPtr> fire(const std::int64_t lastExecutedPrice) {
    std::vector<OrderPtr> fired;
    for (const OrderPtr& stop : stops_) {
      const bool reached = stop->side() == protocol::Side::BUY
                               ? lastExecutedPrice >= stop->triggerPrice()
                               : lastExecutedPrice <= stop->triggerPrice();
      if (reached) {
        fired.push_back(stop);
      }
    }
    std::sort(fired.begin(), fired.end(), byArrival);
    for (const OrderPtr& stop : fired) {
      std::erase(stops_, stop);
    }
    return fired;
  }

 private:
  std::vector<OrderPtr> stops_;
};

}  // namespace io::github::giovanicaprison::matching::naive

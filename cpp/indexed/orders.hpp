// The scans the book and the trigger book share. Both structures are a list walked end to end, and
// finding an order by its owner is the same walk in both. Sharing the loop changes nothing about
// its cost, which stays this rung's to pay; what it removes is two copies of one loop drifting
// apart.

#pragma once

#include <algorithm>
#include <vector>

#include "indexed/order.hpp"

namespace io::github::giovanicaprison::matching::indexed::orders {

// The order one participant gave one client order id to, or nothing when nothing matches.
inline OrderPtr named(const std::vector<OrderPtr>& orders, const std::uint32_t participantId,
                      const std::uint64_t clientOrderId) {
  for (const OrderPtr& order : orders) {
    if (order->participantId() == participantId && order->clientOrderId() == clientOrderId) {
      return order;
    }
  }
  return nullptr;
}

// Everything one participant has, in arrival order, which is how a mass cancel reports.
inline std::vector<OrderPtr> of(const std::vector<OrderPtr>& orders,
                                const std::uint32_t participantId) {
  std::vector<OrderPtr> found;
  for (const OrderPtr& order : orders) {
    if (order->participantId() == participantId) {
      found.push_back(order);
    }
  }
  std::sort(found.begin(), found.end(), byArrival);
  return found;
}

}  // namespace io::github::giovanicaprison::matching::indexed::orders

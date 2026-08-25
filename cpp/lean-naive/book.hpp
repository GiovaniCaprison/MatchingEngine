// One list, scanned, exactly as the full rung's book is. The two engines share their structure on
// purpose: the comparison between them has to isolate the feature set, so a structural difference
// here would put a second variable inside the one question this engine exists to answer.

#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

#include "lean-naive/order.hpp"

namespace io::github::giovanicaprison::matching::lean::naive {

class Book {
 public:
  void add(const OrderPtr& order) { orders_.push_back(order); }

  void remove(const OrderPtr& order) { std::erase(orders_, order); }

  const std::vector<OrderPtr>& orders() const { return orders_; }

  OrderPtr named(const std::uint32_t participantId, const std::uint64_t clientOrderId) const {
    for (const OrderPtr& order : orders_) {
      if (order->participantId() == participantId && order->clientOrderId() == clientOrderId) {
        return order;
      }
    }
    return nullptr;
  }

  // The order a taker reaches next: best price first, then earliest arrival (FR-3.1, FR-3.3).
  OrderPtr nextToTake(const protocol::Side::Value takerSide, const std::int64_t limit) const {
    const protocol::Side::Value restingSide = opposite(takerSide);
    OrderPtr best = nullptr;
    for (const OrderPtr& order : orders_) {
      if (order->side() != restingSide || !crosses(takerSide, limit, order->price())) {
        continue;
      }
      if (best == nullptr || better(restingSide, order->price(), best->price()) ||
          (order->price() == best->price() && order->arrival() < best->arrival())) {
        best = order;
      }
    }
    return best;
  }

  // Every order for one participant, in arrival order, which is how a mass cancel reports.
  std::vector<OrderPtr> of(const std::uint32_t participantId) const {
    std::vector<OrderPtr> found;
    for (const OrderPtr& order : orders_) {
      if (order->participantId() == participantId) {
        found.push_back(order);
      }
    }
    std::sort(found.begin(), found.end(), byArrival);
    return found;
  }

  static protocol::Side::Value opposite(const protocol::Side::Value side) {
    return side == protocol::Side::BUY ? protocol::Side::SELL : protocol::Side::BUY;
  }

  static bool crosses(const protocol::Side::Value takerSide, const std::int64_t limit,
                      const std::int64_t price) {
    if (limit == 0) {
      return true;
    }
    return takerSide == protocol::Side::BUY ? price <= limit : price >= limit;
  }

  static bool better(const protocol::Side::Value side, const std::int64_t price,
                     const std::int64_t than) {
    return side == protocol::Side::BUY ? price > than : price < than;
  }

 private:
  std::vector<OrderPtr> orders_;
};

}  // namespace io::github::giovanicaprison::matching::lean::naive

// One list, scanned. Every question this book answers is a walk over every order it holds, and
// that is the point of rung zero: the cost of not indexing anything is what the rungs above it are
// measured against. The waste that follows from the representation stays; a second scan that no
// representation makes necessary is a defect.

#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

#include "naive/order.hpp"
#include "naive/orders.hpp"

namespace io::github::giovanicaprison::matching::naive {

class Book {
 public:
  void add(const OrderPtr& order) { orders_.push_back(order); }

  void remove(const OrderPtr& order) { std::erase(orders_, order); }

  const std::vector<OrderPtr>& orders() const { return orders_; }

  // The order one participant gave one client order id to. A scan, because an index on the pair
  // is the next rung's idea.
  OrderPtr named(const std::uint32_t participantId, const std::uint64_t clientOrderId) const {
    return orders::named(orders_, participantId, clientOrderId);
  }

  // The order a taker reaches next: best price first, then earliest arrival (FR-3.1, FR-3.3).
  // A limit of zero means the taker has no price of its own, which is what a market order is.
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

  // Everything resting at one price on one side, in arrival order, for a pro-rata allocation.
  std::vector<OrderPtr> atPrice(const protocol::Side::Value side, const std::int64_t price) const {
    std::vector<OrderPtr> found;
    for (const OrderPtr& order : orders_) {
      if (order->side() == side && order->price() == price) {
        found.push_back(order);
      }
    }
    std::sort(found.begin(), found.end(), byArrival);
    return found;
  }

  // Every order for one participant, in arrival order, which is how a mass cancel reports
  // (FR-4.7).
  std::vector<OrderPtr> of(const std::uint32_t participantId) const {
    return orders::of(orders_, participantId);
  }

  // How much of a taker's order the book could fill, for the orders that have to know first.
  std::int64_t fillable(const protocol::Side::Value takerSide, const std::int64_t limit,
                        const std::uint64_t smpId) const {
    std::int64_t total = 0;
    for (const OrderPtr& order : orders_) {
      if (order->side() != opposite(takerSide) || !crosses(takerSide, limit, order->price())) {
        continue;
      }
      if (smpId != 0 && order->smpId() == smpId) {
        continue;
      }
      total += order->remaining();
    }
    return total;
  }

  static protocol::Side::Value opposite(const protocol::Side::Value side) {
    return side == protocol::Side::BUY ? protocol::Side::SELL : protocol::Side::BUY;
  }

  // Whether a taker at limit can reach a resting order at price.
  static bool crosses(const protocol::Side::Value takerSide, const std::int64_t limit,
                      const std::int64_t price) {
    if (limit == 0) {
      return true;
    }
    return takerSide == protocol::Side::BUY ? price <= limit : price >= limit;
  }

  // Which of two prices is in front on one side: higher for a bid, lower for an offer.
  static bool better(const protocol::Side::Value side, const std::int64_t price,
                     const std::int64_t than) {
    return side == protocol::Side::BUY ? price > than : price < than;
  }

 private:
  std::vector<OrderPtr> orders_;
};

}  // namespace io::github::giovanicaprison::matching::naive

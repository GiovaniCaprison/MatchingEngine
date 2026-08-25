// The indexed rung's book with nothing on it the lean remit does not need, mirroring the Java twin:
// sorted levels read from opposite ends of one map shape, intrusive queues, a folded name index,
// and a running level total. The feature machinery is absent: no pro-rata walk, no fillable
// pre-check, no re-queueing, because nothing here replenishes.

#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <unordered_map>
#include <vector>

#include "lean-indexed/order.hpp"

namespace io::github::giovanicaprison::matching::lean::indexed {

class Book {
 public:
  struct Level {
    std::int64_t price = 0;
    OrderPtr head;
    Order* tail = nullptr;
    std::int64_t displayed = 0;
  };

  void add(const OrderPtr& order) {
    Level& level = levelOf(order->side(), order->price());
    append(level, order);
    level.displayed += order->remaining();
    byName_[nameOf(*order)] = order;
  }

  void remove(const OrderPtr& order) {
    auto& levels = side(order->side());
    const auto found = levels.find(order->price());
    Level& level = found->second;
    unlink(level, order);
    level.displayed -= order->remaining();
    if (level.head == nullptr) {
      levels.erase(found);
    }
    byName_.erase(nameOf(*order));
  }

  // The order's remaining quantity changed in place, so the level's total follows.
  void displayedChanged(const OrderPtr& order, const std::int64_t before) {
    levelOf(order->side(), order->price()).displayed += order->remaining() - before;
  }

  OrderPtr named(const std::uint32_t participantId, const std::uint64_t clientOrderId) const {
    const auto found = byName_.find(nameOf(participantId, clientOrderId));
    return found == byName_.end() ? nullptr : found->second;
  }

  // The order a taker reaches next: the front of the best crossing level (FR-3.1, FR-3.3).
  OrderPtr nextToTake(const protocol::Side::Value takerSide, const std::int64_t limit) const {
    const protocol::Side::Value restingSide = opposite(takerSide);
    const Levels& levels = side(restingSide);
    if (levels.empty()) {
      return nullptr;
    }
    const auto& best = restingSide == protocol::Side::BUY ? *levels.rbegin() : *levels.begin();
    if (!crosses(takerSide, limit, best.first)) {
      return nullptr;
    }
    return best.second.head;
  }

  // Every order for one participant, in arrival order. Still a walk, on purpose.
  std::vector<OrderPtr> of(const std::uint32_t participantId) const {
    std::vector<OrderPtr> found;
    for (const auto& [name, order] : byName_) {
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

 private:
  using Levels = std::map<std::int64_t, Level>;

  static std::uint64_t nameOf(const std::uint32_t participantId,
                              const std::uint64_t clientOrderId) {
    return (static_cast<std::uint64_t>(participantId) << 44) ^
           clientOrderId * 0x9E3779B97F4A7C15ULL;
  }

  static std::uint64_t nameOf(const Order& order) {
    return nameOf(order.participantId(), order.clientOrderId());
  }

  Level& levelOf(const protocol::Side::Value which, const std::int64_t price) {
    Level& level = side(which)[price];
    level.price = price;
    return level;
  }

  static void append(Level& level, const OrderPtr& order) {
    order->previous_ = level.tail;
    order->next_ = nullptr;
    if (level.tail == nullptr) {
      level.head = order;
    } else {
      level.tail->next_ = order;
    }
    level.tail = order.get();
  }

  static void unlink(Level& level, const OrderPtr& order) {
    const OrderPtr keep = order;
    if (order->previous_ == nullptr) {
      level.head = order->next_;
    } else {
      order->previous_->next_ = order->next_;
    }
    if (order->next_ == nullptr) {
      level.tail = order->previous_;
    } else {
      order->next_->previous_ = order->previous_;
    }
    keep->previous_ = nullptr;
    keep->next_ = nullptr;
  }

  Levels& side(const protocol::Side::Value which) {
    return which == protocol::Side::BUY ? bids_ : asks_;
  }

  const Levels& side(const protocol::Side::Value which) const {
    return which == protocol::Side::BUY ? bids_ : asks_;
  }

  Levels bids_;
  Levels asks_;
  std::unordered_map<std::uint64_t, OrderPtr> byName_;
};

}  // namespace io::github::giovanicaprison::matching::lean::indexed

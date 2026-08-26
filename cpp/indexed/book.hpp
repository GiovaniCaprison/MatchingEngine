// Sorted price levels, an index by the id a cancel carries, and intrusive queues, mirroring the
// Java rung structure for structure. The questions the naive book answered with a walk are lookups
// here, and the price of the lookups is bookkeeping that can drift, which is what the invariants
// on the Java side police (NFR-3.1, NFR-3.2) and the differential holds identical across languages.
//
// What this rung still does not index is deliberate: a participant's orders are found by walking,
// because a mass cancel is a large command wherever it lands (P-9), and the trigger book stays a
// scanned list, so the step from rung zero isolates the book's structure and changes nothing else.

#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <unordered_map>
#include <vector>

#include "indexed/order.hpp"

namespace io::github::giovanicaprison::matching::indexed {

class Book {
 public:
  // One price: its queue as an intrusive chain in arrival order, and the running total of what it
  // displays, never recomputed on the hot path.
  struct Level {
    std::int64_t price = 0;
    OrderPtr head;
    Order* tail = nullptr;
    std::int64_t displayed = 0;

    // The queue front to back, materialised for the walks that want a list and for the tests.
    std::vector<OrderPtr> queue() const {
      std::vector<OrderPtr> orders;
      for (OrderPtr order = head; order != nullptr; order = order->next_) {
        orders.push_back(order);
      }
      return orders;
    }
  };

  void add(const OrderPtr& order) {
    Level& level = levelOf(order->side(), order->price());
    append(level, order);
    level.displayed += order->displayed();
    byName_[nameOf(*order)] = order;
  }

  void remove(const OrderPtr& order) {
    auto& levels = side(order->side());
    const auto found = levels.find(order->price());
    Level& level = found->second;
    unlink(level, order);
    level.displayed -= order->displayed();
    if (level.head == nullptr) {
      // (NFR-3.2) An empty level does not survive.
      levels.erase(found);
    }
    byName_.erase(nameOf(*order));
  }

  // The order's displayed quantity changed in place, so the level's total follows (NFR-3.1).
  void displayedChanged(const OrderPtr& order, const std::int64_t before) {
    levelOf(order->side(), order->price()).displayed += order->displayed() - before;
  }

  // A replenished tranche joins the back of the queue at its price (FR-5.4).
  void requeued(const OrderPtr& order, const std::int64_t displayedBefore) {
    Level& level = levelOf(order->side(), order->price());
    unlink(level, order);
    append(level, order);
    level.displayed += order->displayed() - displayedBefore;
  }

  OrderPtr named(const std::uint32_t participantId, const std::uint64_t clientOrderId) const {
    const auto found = byName_.find(nameOf(participantId, clientOrderId));
    return found == byName_.end() ? nullptr : found->second;
  }

  // The order a taker reaches next: the front of the best crossing level (FR-3.1, FR-3.3). The
  // best bid is the highest price and the best ask the lowest, so the two sides are read from
  // opposite ends of one map shape.
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

  // Everything resting at one price on one side, in arrival order, for a pro-rata allocation.
  std::vector<OrderPtr> atPrice(const protocol::Side::Value side, const std::int64_t price) const {
    const auto& levels = this->side(side);
    const auto found = levels.find(price);
    return found == levels.end() ? std::vector<OrderPtr>{} : found->second.queue();
  }

  // Every order for one participant, in arrival order (FR-4.7). Still a walk, on purpose.
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

  // How much a taker could fill, summed over crossing levels only rather than the whole book.
  std::int64_t fillable(const protocol::Side::Value takerSide, const std::int64_t limit,
                        const std::uint64_t smpId) const {
    const protocol::Side::Value restingSide = opposite(takerSide);
    const Levels& levels = side(restingSide);
    if (restingSide == protocol::Side::BUY) {
      return fillableOver(levels.rbegin(), levels.rend(), takerSide, limit, smpId);
    }
    return fillableOver(levels.begin(), levels.end(), takerSide, limit, smpId);
  }

  // Every resting order, for the auction's walks and for the tests that hold the book still.
  std::vector<OrderPtr> orders() const {
    std::vector<OrderPtr> all;
    all.reserve(byName_.size());
    for (const auto& [name, order] : byName_) {
      all.push_back(order);
    }
    return all;
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
  // One map shape for both sides, ascending by price; the best bid reads from the back and the
  // best ask from the front. A map's nodes are stable, so a level's address survives its
  // neighbours coming and going.
  using Levels = std::map<std::int64_t, Level>;

  static std::uint64_t nameOf(const std::uint32_t participantId,
                              const std::uint64_t clientOrderId) {
    // The pair a cancel carries, folded to one key. A collision would fail the corpus and the
    // differential loudly rather than quietly, and none has anything to collide with in practice:
    // participants are small integers and client order ids are ordinals or a feed's references.
    return (static_cast<std::uint64_t>(participantId) << 44) ^
           clientOrderId * 0x9E3779B97F4A7C15ULL;
  }

  static std::uint64_t nameOf(const Order& order) {
    return nameOf(order.participantId(), order.clientOrderId());
  }

  template <typename Iterator>
  std::int64_t fillableOver(Iterator at, const Iterator& end, const protocol::Side::Value takerSide,
                            const std::int64_t limit, const std::uint64_t smpId) const {
    std::int64_t total = 0;
    for (; at != end; ++at) {
      if (!crosses(takerSide, limit, at->first)) {
        return total;
      }
      for (OrderPtr order = at->second.head; order != nullptr; order = order->next_) {
        if (smpId == 0 || order->smpId() != smpId) {
          total += order->remaining();
        }
      }
    }
    return total;
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
    // The copy is the point: it keeps the order alive while the owning forward chain lets go.
    // NOLINTNEXTLINE(performance-unnecessary-copy-initialization)
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

}  // namespace io::github::giovanicaprison::matching::indexed

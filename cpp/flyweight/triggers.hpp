// The stops that have not fired, chained through the slab's own links in arrival order. A resting
// stop is not liquidity and is invisible to the book: it is a condition evaluated against the last
// executed price, and on firing it becomes an ordinary order (FR-6.1, FR-6.3). Still scanned,
// deliberately: firing order is arrival order among the reached, which the chain already is
// because stops only ever join at the back, and the stop book stays small enough that an index
// would cost more than the walk it saves (P-16).

#pragma once

#include <cstdint>
#include <vector>

#include "flyweight/slab.hpp"

namespace io::github::giovanicaprison::matching::flyweight {

class Triggers {
 public:
  explicit Triggers(Slab& slab) : slab_(slab) {}

  void add(const std::int32_t slot) {
    slab_.link(slot, tail_, 0);
    if (tail_ == 0) {
      head_ = slot;
    } else {
      slab_.linkNext(tail_, slot);
    }
    tail_ = slot;
  }

  void remove(const std::int32_t slot) {
    const std::int32_t previous = slab_.previous(slot);
    const std::int32_t next = slab_.next(slot);
    if (previous == 0) {
      head_ = next;
    } else {
      slab_.linkNext(previous, next);
    }
    if (next == 0) {
      tail_ = previous;
    } else {
      slab_.linkPrevious(next, previous);
    }
    slab_.link(slot, 0, 0);
  }

  std::int32_t named(const std::uint32_t participantId, const std::uint64_t clientOrderId) const {
    for (std::int32_t slot = head_; slot != 0; slot = slab_.next(slot)) {
      if (slab_.participantId(slot) == participantId &&
          slab_.clientOrderId(slot) == clientOrderId) {
        return slot;
      }
    }
    return 0;
  }

  void of(const std::uint32_t participantId, std::vector<std::int32_t>& into) const {
    for (std::int32_t slot = head_; slot != 0; slot = slab_.next(slot)) {
      if (slab_.participantId(slot) == participantId) {
        into.push_back(slot);
      }
    }
  }

  // Moves the stops the last executed price has reached into the caller's queue, earliest first,
  // removed as they go (FR-6.2).
  void fire(const std::int64_t lastExecutedPrice, std::vector<std::int32_t>& into) {
    std::int32_t slot = head_;
    while (slot != 0) {
      const std::int32_t following = slab_.next(slot);
      const bool reached = slab_.side(slot) == 0 ? lastExecutedPrice >= slab_.triggerPrice(slot)
                                                 : lastExecutedPrice <= slab_.triggerPrice(slot);
      if (reached) {
        remove(slot);
        into.push_back(slot);
      }
      slot = following;
    }
  }

 private:
  Slab& slab_;
  std::int32_t head_ = 0;
  std::int32_t tail_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::flyweight

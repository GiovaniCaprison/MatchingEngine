// The stops that have not fired. A resting stop is not liquidity and is invisible to the book: it
// is a condition evaluated against the last executed price, and on firing it becomes an ordinary
// order (FR-6.1, FR-6.3). Still scanned, deliberately, so the step from the rung below stays about
// allocation. What changed is the container: the stops chain through their own links in arrival
// order, so joining, leaving and firing move pointers and nothing here allocates (NFR-4.3).

#pragma once

#include <cstdint>
#include <vector>

#include "pooled/order.hpp"

namespace io::github::giovanicaprison::matching::pooled {

class Triggers {
 public:
  void add(const OrderPtr stop) {
    stop->previous_ = tail_;
    stop->next_ = nullptr;
    if (tail_ == nullptr) {
      head_ = stop;
    } else {
      tail_->next_ = stop;
    }
    tail_ = stop;
  }

  void remove(const OrderPtr stop) {
    if (stop->previous_ == nullptr) {
      head_ = stop->next_;
    } else {
      stop->previous_->next_ = stop->next_;
    }
    if (stop->next_ == nullptr) {
      tail_ = stop->previous_;
    } else {
      stop->next_->previous_ = stop->previous_;
    }
    stop->previous_ = nullptr;
    stop->next_ = nullptr;
  }

  OrderPtr named(const std::uint32_t participantId, const std::uint64_t clientOrderId) const {
    for (OrderPtr stop = head_; stop != nullptr; stop = stop->next_) {
      if (stop->participantId() == participantId && stop->clientOrderId() == clientOrderId) {
        return stop;
      }
    }
    return nullptr;
  }

  void of(const std::uint32_t participantId, std::vector<OrderPtr>& into) const {
    for (OrderPtr stop = head_; stop != nullptr; stop = stop->next_) {
      if (stop->participantId() == participantId) {
        into.push_back(stop);
      }
    }
  }

  // Moves the stops the last executed price has reached into the caller's queue, earliest first,
  // removed as they go (FR-6.2). The chain is in arrival order because stops only ever join at
  // the back, so walking it front to back is already the order the rung below sorted into.
  void fire(const std::int64_t lastExecutedPrice, std::vector<OrderPtr>& into) {
    OrderPtr stop = head_;
    while (stop != nullptr) {
      const OrderPtr following = stop->next_;
      const bool reached = stop->side() == protocol::Side::BUY
                               ? lastExecutedPrice >= stop->triggerPrice()
                               : lastExecutedPrice <= stop->triggerPrice();
      if (reached) {
        remove(stop);
        into.push_back(stop);
      }
      stop = following;
    }
  }

 private:
  OrderPtr head_ = nullptr;
  OrderPtr tail_ = nullptr;
};

}  // namespace io::github::giovanicaprison::matching::pooled

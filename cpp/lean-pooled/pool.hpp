// Where orders come from and where they go back to, which is the rung (NFR-4.3). A free list
// threaded through the orders' own links over storage that never moves, so the pool is pointer
// swaps. It grows by a block when it runs dry, which prices growth where it belongs: the
// high-water mark of live orders is paid for on the way up, and the steady state after it
// allocates nothing, which is what the allocation probe holds it to.

#pragma once

#include <cstddef>
#include <deque>

#include "lean-pooled/order.hpp"

namespace io::github::giovanicaprison::matching::lean::pooled {

class Pool {
 public:
  explicit Pool(const std::size_t preallocated) {
    for (std::size_t i = 0; i < preallocated; i++) {
      storage_.emplace_back();
      release(&storage_.back());
    }
  }

  OrderPtr acquire() {
    if (free_ == nullptr) {
      storage_.emplace_back();
      return &storage_.back();
    }
    const OrderPtr order = free_;
    free_ = order->next_;
    order->next_ = nullptr;
    return order;
  }

  // The order must already be detached from every structure (P-13); the pool checks nothing.
  void release(const OrderPtr order) {
    order->previous_ = nullptr;
    order->next_ = free_;
    free_ = order;
  }

 private:
  std::deque<Order> storage_;
  Order* free_ = nullptr;
};

}  // namespace io::github::giovanicaprison::matching::lean::pooled

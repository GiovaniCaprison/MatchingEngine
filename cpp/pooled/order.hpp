// One order, mutable and reusable, with every kind of order in the same shape (P-7). This rung's
// variable is allocation, so nothing here is const and orders pass by raw pointer: the pool owns
// the storage, every structure detaches an order completely on exit (P-13), and an order's state
// is a function of its most recent init. The shared_ptr of the rungs below goes with the
// allocation it exists to manage.

#pragma once

#include <algorithm>
#include <cstdint>

#include "io_github_giovanicaprison_matching_protocol/PricingInstruction.h"
#include "io_github_giovanicaprison_matching_protocol/Side.h"
#include "io_github_giovanicaprison_matching_protocol/TimeInForce.h"

namespace io::github::giovanicaprison::matching::pooled {

class Order;
using OrderPtr = Order*;

class Order {
 public:
  Order() = default;

  // A fresh life for a pooled object: every field is written, nothing survives the last one.
  void init(const std::uint64_t id, const std::uint64_t clientOrderId,
            const std::uint32_t participantId, const protocol::Side::Value side,
            const protocol::PricingInstruction::Value pricing,
            const protocol::TimeInForce::Value timeInForce, const bool postOnly,
            const std::int64_t price, const std::int64_t quantity, const std::int64_t minQuantity,
            const std::int64_t displayQuantity, const std::int64_t triggerPrice,
            const std::uint64_t smpId, const std::int64_t arrival, const std::int64_t executed) {
    id_ = id;
    clientOrderId_ = clientOrderId;
    participantId_ = participantId;
    side_ = side;
    pricing_ = pricing;
    timeInForce_ = timeInForce;
    postOnly_ = postOnly;
    minQuantity_ = minQuantity;
    triggerPrice_ = triggerPrice;
    smpId_ = smpId;
    displaySize_ = displayQuantity;
    price_ = price;
    remaining_ = quantity;
    displayed_ = displayQuantity == 0 ? quantity : std::min(displayQuantity, quantity);
    arrival_ = arrival;
    executed_ = executed;
  }

  std::uint64_t id() const { return id_; }
  std::uint64_t clientOrderId() const { return clientOrderId_; }
  std::uint32_t participantId() const { return participantId_; }
  protocol::Side::Value side() const { return side_; }
  protocol::PricingInstruction::Value pricing() const { return pricing_; }
  protocol::TimeInForce::Value timeInForce() const { return timeInForce_; }
  bool postOnly() const { return postOnly_; }
  std::int64_t price() const { return price_; }
  std::int64_t remaining() const { return remaining_; }

  // What the feed has been told about, which is never the hidden part (FR-5.2).
  std::int64_t displayed() const { return displayed_; }
  std::int64_t minQuantity() const { return minQuantity_; }
  std::int64_t triggerPrice() const { return triggerPrice_; }
  std::uint64_t smpId() const { return smpId_; }
  std::int64_t arrival() const { return arrival_; }

  // How much of this order has traded, over its whole life and across every replace. A replace
  // names the order's total quantity, so this is what the remainder is worked out from (FR-4.9).
  std::int64_t executed() const { return executed_; }

  // The tranche size an iceberg shows at a time, which a replace has to preserve (FR-4.10).
  std::int64_t displaySize() const { return displaySize_; }

  // Whether this order would trade at a candidate price: at it, or better from its own side.
  bool willingAt(const std::int64_t candidate) const {
    return side_ == protocol::Side::BUY ? price_ >= candidate : price_ <= candidate;
  }

  // A stop rests in the trigger book and is not book liquidity (FR-6.1).
  bool stop() const { return triggerPrice_ != 0; }

  bool restsOnRemainder() const {
    return pricing_ == protocol::PricingInstruction::LIMIT &&
           (timeInForce_ == protocol::TimeInForce::GOOD_TILL_CANCEL ||
            timeInForce_ == protocol::TimeInForce::DAY);
  }

  // Takes quantity from the displayed part first, since that is all a taker can see. Returns
  // whether the displayed part is now empty while quantity remains, which is when a further
  // tranche is displayed and joins the back of its queue (FR-5.4).
  bool take(const std::int64_t quantity) {
    remaining_ -= quantity;
    executed_ += quantity;
    displayed_ -= quantity;
    return displayed_ == 0 && remaining_ > 0;
  }

  // This order is joining the queue at its price: what it shows and where it stands are settled
  // now, rather than when the command arrived, so it queues behind anything that joined while it
  // was walking. The same operation serves a replenishment, because that is the same thing.
  void rest(const std::int64_t arrivalSequence) {
    displayed_ = displaySize_ == 0 ? remaining_ : std::min(displaySize_, remaining_);
    arrival_ = arrivalSequence;
  }

  // A replace that keeps queue position (FR-4.4) changes what is left and nothing else.
  void reduceTo(const std::int64_t remainder) {
    remaining_ = remainder;
    displayed_ = displaySize_ == 0 ? remainder : std::min(displaySize_, remainder);
  }

  // A triggered stop becomes an ordinary order of its own pricing instruction (FR-6.3). The rung
  // below built a fresh object for the fired order; here the stop changes in place, because it is
  // the same order and building another is an allocation with no new information in it.
  OrderPtr triggered(const std::int64_t arrivalSequence) {
    triggerPrice_ = 0;
    arrival_ = arrivalSequence;
    return this;
  }

 private:
  // The order's own place in whichever chain holds it: a price level's queue, the trigger list,
  // or the pool's free list, never more than one at a time (P-13).
  Order* next_ = nullptr;
  Order* previous_ = nullptr;

  friend class Auction;
  friend class Book;
  friend class Pool;
  friend class PooledEngine;
  friend class Triggers;

  std::uint64_t id_ = 0;
  std::uint64_t clientOrderId_ = 0;
  std::uint32_t participantId_ = 0;
  protocol::Side::Value side_ = protocol::Side::NULL_VALUE;
  protocol::PricingInstruction::Value pricing_ = protocol::PricingInstruction::NULL_VALUE;
  protocol::TimeInForce::Value timeInForce_ = protocol::TimeInForce::NULL_VALUE;
  bool postOnly_ = false;
  std::int64_t minQuantity_ = 0;
  std::int64_t triggerPrice_ = 0;
  std::uint64_t smpId_ = 0;
  std::int64_t displaySize_ = 0;

  std::int64_t price_ = 0;
  std::int64_t remaining_ = 0;
  std::int64_t displayed_ = 0;
  std::int64_t arrival_ = 0;
  std::int64_t executed_ = 0;
};

// Earliest first, which is every tie-break and every report order in the venue.
inline bool byArrival(const Order* left, const Order* right) {
  return left->arrival() < right->arrival();
}

}  // namespace io::github::giovanicaprison::matching::pooled

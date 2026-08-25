// One order with nothing on it a limit or market order does not need, mutable and reusable. This
// is the object the comparison is about: the full engine's order carries a trigger price, a
// display size, a minimum quantity, a self match id and a flag, and every one of them occupies the
// layout whether or not the flow uses it. Here they do not exist, which is the only honest way to
// measure what their existing costs (P-16). And like the rung it shadows, orders pass by raw
// pointer: the pool owns the storage and an order's state is a function of its most recent init
// (NFR-4.3, P-13).

#pragma once

#include <cstdint>

#include "io_github_giovanicaprison_matching_protocol/PricingInstruction.h"
#include "io_github_giovanicaprison_matching_protocol/Side.h"
#include "io_github_giovanicaprison_matching_protocol/TimeInForce.h"

namespace io::github::giovanicaprison::matching::lean::pooled {

class Order;
using OrderPtr = Order*;

class Order {
 public:
  Order() = default;

  // A fresh life for a pooled object: every field is written, nothing survives the last one.
  void init(const std::uint64_t id, const std::uint64_t clientOrderId,
            const std::uint32_t participantId, const protocol::Side::Value side,
            const protocol::PricingInstruction::Value pricing,
            const protocol::TimeInForce::Value timeInForce, const std::int64_t price,
            const std::int64_t quantity, const std::int64_t arrival, const std::int64_t executed) {
    id_ = id;
    clientOrderId_ = clientOrderId;
    participantId_ = participantId;
    side_ = side;
    pricing_ = pricing;
    timeInForce_ = timeInForce;
    price_ = price;
    remaining_ = quantity;
    arrival_ = arrival;
    executed_ = executed;
  }

  std::uint64_t id() const { return id_; }
  std::uint64_t clientOrderId() const { return clientOrderId_; }
  std::uint32_t participantId() const { return participantId_; }
  protocol::Side::Value side() const { return side_; }
  protocol::PricingInstruction::Value pricing() const { return pricing_; }
  protocol::TimeInForce::Value timeInForce() const { return timeInForce_; }
  std::int64_t price() const { return price_; }

  // What is left is what is shown. Without icebergs the two are the same number.
  std::int64_t remaining() const { return remaining_; }
  std::int64_t arrival() const { return arrival_; }

  // What has traded across the order's whole life, which a replace works its remainder from.
  std::int64_t executed() const { return executed_; }

  bool restsOnRemainder() const {
    return pricing_ == protocol::PricingInstruction::LIMIT &&
           (timeInForce_ == protocol::TimeInForce::GOOD_TILL_CANCEL ||
            timeInForce_ == protocol::TimeInForce::DAY);
  }

  void take(const std::int64_t quantity) {
    remaining_ -= quantity;
    executed_ += quantity;
  }

  void rest(const std::int64_t arrivalSequence) { arrival_ = arrivalSequence; }

  void reduceTo(const std::int64_t remainder) { remaining_ = remainder; }

 private:
  // The order's own place in whichever chain holds it: its level's queue or the pool's free list,
  // never both (P-13).
  Order* next_ = nullptr;
  Order* previous_ = nullptr;

  friend class Book;
  friend class Pool;

  std::uint64_t id_ = 0;
  std::uint64_t clientOrderId_ = 0;
  std::uint32_t participantId_ = 0;
  protocol::Side::Value side_ = protocol::Side::NULL_VALUE;
  protocol::PricingInstruction::Value pricing_ = protocol::PricingInstruction::NULL_VALUE;
  protocol::TimeInForce::Value timeInForce_ = protocol::TimeInForce::NULL_VALUE;
  std::int64_t price_ = 0;

  std::int64_t remaining_ = 0;
  std::int64_t arrival_ = 0;
  std::int64_t executed_ = 0;
};

// Earliest first, which is every tie-break and every report order in the venue.
inline bool byArrival(const Order* left, const Order* right) {
  return left->arrival() < right->arrival();
}

}  // namespace io::github::giovanicaprison::matching::lean::pooled

// One order with nothing on it a limit or market order does not need. This is the object the
// comparison is about: the full engine's order carries a trigger price, a display size, a minimum
// quantity, a self match id and a flag, and every one of them occupies the layout whether or not
// the flow uses it. Here they do not exist, which is the only honest way to measure what their
// existing costs (P-16).

#pragma once

#include <cstdint>
#include <memory>

#include "io_github_giovanicaprison_matching_protocol/PricingInstruction.h"
#include "io_github_giovanicaprison_matching_protocol/Side.h"
#include "io_github_giovanicaprison_matching_protocol/TimeInForce.h"

namespace io::github::giovanicaprison::matching::lean {

class Order;
using OrderPtr = std::shared_ptr<Order>;

class Order {
 public:
  Order(const std::uint64_t id, const std::uint64_t clientOrderId,
        const std::uint32_t participantId, const protocol::Side::Value side,
        const protocol::PricingInstruction::Value pricing,
        const protocol::TimeInForce::Value timeInForce, const std::int64_t price,
        const std::int64_t quantity, const std::int64_t arrival, const std::int64_t executed)
      : id_(id),
        clientOrderId_(clientOrderId),
        participantId_(participantId),
        side_(side),
        pricing_(pricing),
        timeInForce_(timeInForce),
        price_(price),
        remaining_(quantity),
        arrival_(arrival),
        executed_(executed) {}

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
  const std::uint64_t id_;
  const std::uint64_t clientOrderId_;
  const std::uint32_t participantId_;
  const protocol::Side::Value side_;
  const protocol::PricingInstruction::Value pricing_;
  const protocol::TimeInForce::Value timeInForce_;
  const std::int64_t price_;

  std::int64_t remaining_;
  std::int64_t arrival_;
  std::int64_t executed_;
};

inline bool byArrival(const OrderPtr& left, const OrderPtr& right) {
  return left->arrival() < right->arrival();
}

}  // namespace io::github::giovanicaprison::matching::lean

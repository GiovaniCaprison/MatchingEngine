#include "lean/lean_engine.hpp"

#include <algorithm>
#include <cstdlib>
#include <stdexcept>
#include <string>
#include <vector>

#include "io_github_giovanicaprison_matching_protocol/CancelOrder.h"
#include "io_github_giovanicaprison_matching_protocol/InstrumentDefinition.h"
#include "io_github_giovanicaprison_matching_protocol/MassCancel.h"
#include "io_github_giovanicaprison_matching_protocol/MessageHeader.h"
#include "io_github_giovanicaprison_matching_protocol/ReplaceOrder.h"
#include "io_github_giovanicaprison_matching_protocol/SessionStateChange.h"

namespace io::github::giovanicaprison::matching::lean {

namespace {

using protocol::MessageHeader;
using protocol::PricingInstruction;
using protocol::RejectReason;
using protocol::RemoveReason;
using protocol::SessionState;
using protocol::TimeInForce;

constexpr RejectReason::Value NOTHING_WRONG = RejectReason::NULL_VALUE;

template <typename Decoder>
Decoder decoded(char* buffer, const std::size_t body, const MessageHeader& header,
                const std::size_t end) {
  Decoder decoder;
  decoder.wrapForDecode(buffer, body, header.blockLength(), header.version(), end);
  return decoder;
}

std::int64_t limitOf(const Order& order) {
  return order.pricing() == PricingInstruction::MARKET ? 0 : order.price();
}

}  // namespace

void LeanEngine::onCommand(char* buffer, const std::size_t offset, const std::size_t length) {
  MessageHeader header;
  const std::size_t end = offset + length;
  header.wrap(buffer, offset, 0, end);
  const std::size_t body = offset + MessageHeader::encodedLength();
  switch (header.templateId()) {
    case protocol::InstrumentDefinition::sbeTemplateId(): {
      auto definition = decoded<protocol::InstrumentDefinition>(buffer, body, header, end);
      tickSize_ = definition.tickSize();
      lotSize_ = definition.lotSize();
      minPrice_ = definition.minPrice();
      maxPrice_ = definition.maxPrice();
      bandWidth_ = definition.bandWidth();
      reference_ = definition.openingReference();
      feed_.instrument(definition.frame().instrumentId());
      return;
    }
    case protocol::NewOrder::sbeTemplateId(): {
      auto newOrder = decoded<protocol::NewOrder>(buffer, body, header, end);
      enter(newOrder);
      return;
    }
    case protocol::CancelOrder::sbeTemplateId(): {
      auto command = decoded<protocol::CancelOrder>(buffer, body, header, end);
      cancel(command.clientOrderId(), command.participantId());
      return;
    }
    case protocol::ReplaceOrder::sbeTemplateId(): {
      auto command = decoded<protocol::ReplaceOrder>(buffer, body, header, end);
      replace(command.clientOrderId(), command.participantId(), command.quantity(),
              command.price());
      return;
    }
    case protocol::MassCancel::sbeTemplateId(): {
      auto command = decoded<protocol::MassCancel>(buffer, body, header, end);
      massCancel(command.clientOrderId(), command.participantId());
      return;
    }
    case protocol::SessionStateChange::sbeTemplateId(): {
      auto command = decoded<protocol::SessionStateChange>(buffer, body, header, end);
      state_ = command.state();
      feed_.stateChanged(state_);
      return;
    }
    default:
      throw std::invalid_argument("template " + std::to_string(header.templateId()) +
                                  " is not a command (P-14)");
  }
}

void LeanEngine::enter(protocol::NewOrder& newOrder) {
  const std::uint64_t clientOrderId = newOrder.clientOrderId();
  const std::uint32_t participantId = newOrder.participantId();
  const RejectReason::Value refusal = refusalFor(newOrder);
  if (refusal != NOTHING_WRONG) {
    feed_.rejected(clientOrderId, participantId, refusal);
    return;
  }
  const OrderPtr order = std::make_shared<Order>(
      nextOrderId_++, clientOrderId, participantId, newOrder.side(), newOrder.pricing(),
      newOrder.timeInForce(), newOrder.price(), newOrder.quantity(), ++arrival_, 0);
  feed_.accepted(*order);
  if (matching()) {
    match(order);
  }
  settle(order);
}

void LeanEngine::settle(const OrderPtr& order) {
  if (order->remaining() == 0) {
    return;
  }
  if (order->restsOnRemainder()) {
    order->rest(++arrival_);
    book_.add(order);
    feed_.rested(*order);
    return;
  }
  feed_.removed(order->id(), order->remaining(), RemoveReason::IMMEDIATE_OR_CANCEL_REMAINDER);
}

// (FR-3.1, FR-3.3) Best price first, then earliest arrival, until nothing crosses.
void LeanEngine::match(const OrderPtr& taker) {
  while (taker->remaining() > 0) {
    const OrderPtr resting = book_.nextToTake(taker->side(), limitOf(*taker));
    if (resting == nullptr) {
      return;
    }
    const std::int64_t quantity = std::min(taker->remaining(), resting->remaining());
    const std::int64_t price = resting->price();
    taker->take(quantity);
    resting->take(quantity);
    feed_.executed(nextExecutionId_++, taker->id(), resting->id(), price, quantity);
    reference_ = price;
    if (resting->remaining() == 0) {
      book_.remove(resting);
    }
  }
}

void LeanEngine::cancel(const std::uint64_t clientOrderId, const std::uint32_t participantId) {
  if (state_ == SessionState::CLOSED) {
    feed_.rejected(clientOrderId, participantId, RejectReason::STATE_NOT_PERMITTED);
    return;
  }
  const OrderPtr resting = book_.named(participantId, clientOrderId);
  if (resting == nullptr) {
    feed_.rejected(clientOrderId, participantId, RejectReason::UNKNOWN_ORDER);
    return;
  }
  book_.remove(resting);
  feed_.removed(resting->id(), resting->remaining(), RemoveReason::CANCELLED);
}

void LeanEngine::replace(const std::uint64_t clientOrderId, const std::uint32_t participantId,
                         const std::int64_t quantity, const std::int64_t price) {
  if (state_ == SessionState::CLOSED) {
    feed_.rejected(clientOrderId, participantId, RejectReason::STATE_NOT_PERMITTED);
    return;
  }
  const OrderPtr resting = book_.named(participantId, clientOrderId);
  if (resting == nullptr) {
    feed_.rejected(clientOrderId, participantId, RejectReason::UNKNOWN_ORDER);
    return;
  }
  const RejectReason::Value refusal = refusalForReplace(*resting, quantity, price);
  if (refusal != NOTHING_WRONG) {
    feed_.rejected(clientOrderId, participantId, refusal);
    return;
  }
  const std::int64_t remainder = quantity - resting->executed();
  if (price == resting->price() && remainder < resting->remaining()) {
    resting->reduceTo(remainder);
    feed_.reduced(*resting);
    return;
  }
  book_.remove(resting);
  feed_.replaced(*resting, resting->remaining());
  const OrderPtr replacement =
      std::make_shared<Order>(resting->id(), resting->clientOrderId(), resting->participantId(),
                              resting->side(), resting->pricing(), resting->timeInForce(), price,
                              remainder, ++arrival_, resting->executed());
  if (matching()) {
    match(replacement);
  }
  settle(replacement);
}

void LeanEngine::massCancel(const std::uint64_t clientOrderId, const std::uint32_t participantId) {
  if (state_ == SessionState::CLOSED) {
    feed_.rejected(clientOrderId, participantId, RejectReason::STATE_NOT_PERMITTED);
    return;
  }
  const std::vector<OrderPtr> everything = book_.of(participantId);
  for (const OrderPtr& order : everything) {
    book_.remove(order);
    feed_.removed(order->id(), order->remaining(), RemoveReason::MASS_CANCELLED);
  }
}

RejectReason::Value LeanEngine::refusalFor(protocol::NewOrder& newOrder) const {
  if (state_ == SessionState::CLOSED) {
    return RejectReason::STATE_NOT_PERMITTED;
  }
  const std::int64_t quantity = newOrder.quantity();
  if (quantity <= 0) {
    return RejectReason::NON_POSITIVE_QUANTITY;
  }
  if (quantity % lotSize_ != 0) {
    return RejectReason::LOT_VIOLATION;
  }
  const PricingInstruction::Value pricing = newOrder.pricing();
  const TimeInForce::Value timeInForce = newOrder.timeInForce();
  if (pricing == PricingInstruction::MARKET &&
      (timeInForce == TimeInForce::GOOD_TILL_CANCEL || timeInForce == TimeInForce::DAY)) {
    // (VR-3.1) A market order cannot rest, so it cannot be told to.
    return RejectReason::INVALID_FIELDS;
  }
  if (pricing == PricingInstruction::LIMIT) {
    return refusalForPrice(newOrder.price());
  }
  return NOTHING_WRONG;
}

RejectReason::Value LeanEngine::refusalForPrice(const std::int64_t price) const {
  if (price <= 0) {
    return RejectReason::NON_POSITIVE_PRICE;
  }
  if (price % tickSize_ != 0) {
    return RejectReason::TICK_VIOLATION;
  }
  if (price < minPrice_ || price > maxPrice_) {
    return RejectReason::STATIC_BAND_VIOLATION;
  }
  if (std::abs(price - reference_) > bandWidth_) {
    return RejectReason::DYNAMIC_BAND_VIOLATION;
  }
  return NOTHING_WRONG;
}

RejectReason::Value LeanEngine::refusalForReplace(const Order& resting, const std::int64_t quantity,
                                                  const std::int64_t price) const {
  if (quantity <= 0) {
    return RejectReason::NON_POSITIVE_QUANTITY;
  }
  if (quantity <= resting.executed()) {
    return RejectReason::QUANTITY_BELOW_EXECUTED;
  }
  if (quantity % lotSize_ != 0) {
    return RejectReason::LOT_VIOLATION;
  }
  return refusalForPrice(price);
}

}  // namespace io::github::giovanicaprison::matching::lean

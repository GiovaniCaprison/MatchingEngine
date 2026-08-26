#include "lean-flyweight/lean_engine.hpp"

#include <algorithm>
#include <cstdlib>
#include <stdexcept>
#include <string>

#include "io_github_giovanicaprison_matching_protocol/CancelOrder.h"
#include "io_github_giovanicaprison_matching_protocol/MassCancel.h"
#include "io_github_giovanicaprison_matching_protocol/MessageHeader.h"
#include "io_github_giovanicaprison_matching_protocol/PricingInstruction.h"
#include "io_github_giovanicaprison_matching_protocol/RejectReason.h"
#include "io_github_giovanicaprison_matching_protocol/RemoveReason.h"
#include "io_github_giovanicaprison_matching_protocol/ReplaceOrder.h"
#include "io_github_giovanicaprison_matching_protocol/SessionStateChange.h"
#include "io_github_giovanicaprison_matching_protocol/TimeInForce.h"

namespace io::github::giovanicaprison::matching::lean::flyweight {

namespace {

using protocol::MessageHeader;
using protocol::PricingInstruction;
using protocol::RejectReason;
using protocol::RemoveReason;
using protocol::SessionState;
using protocol::TimeInForce;

// The schema's numbers as plain ints, so side arithmetic and the verdict encoding read as the
// Java twin's do: BUY encodes to zero, so side ^ 1 is always the opposite side.
constexpr std::int32_t LIMIT = PricingInstruction::LIMIT;
constexpr std::int32_t MARKET = PricingInstruction::MARKET;
constexpr std::int32_t DAY = TimeInForce::DAY;
constexpr std::int32_t CONTINUOUS = SessionState::CONTINUOUS;
constexpr std::int32_t CLOSED = SessionState::CLOSED;

// A refusal encoded below zero, so one verdict carries either the reason or the tick.
constexpr std::int64_t refusal(const RejectReason::Value reason) {
  return -1 - static_cast<std::int64_t>(reason);
}

RejectReason::Value reasonOf(const std::int64_t verdict) {
  return static_cast<RejectReason::Value>(-1 - verdict);
}

template <typename Decoder>
Decoder decoded(char* buffer, const std::size_t body, const MessageHeader& header,
                const std::size_t end) {
  Decoder decoder;
  decoder.wrapForDecode(buffer, body, header.blockLength(), header.version(), end);
  return decoder;
}

}  // namespace

void LeanEngine::onCommand(char* buffer, const std::size_t offset, const std::size_t length) {
  MessageHeader header;
  const std::size_t end = offset + length;
  header.wrap(buffer, offset, 0, end);
  const std::size_t body = offset + MessageHeader::encodedLength();
  switch (header.templateId()) {
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
      feed_.stateChanged(static_cast<SessionState::Value>(state_));
      return;
    }
    case protocol::InstrumentDefinition::sbeTemplateId(): {
      auto definition = decoded<protocol::InstrumentDefinition>(buffer, body, header, end);
      define(definition);
      return;
    }
    default:
      throw std::invalid_argument("template " + std::to_string(header.templateId()) +
                                  " is not a command (P-14)");
  }
}

// The definition arrives once, before every other command (FR-1.1), which is what sizes the
// ladder.
void LeanEngine::define(protocol::InstrumentDefinition& definition) {
  tickSize_ = definition.tickSize();
  lotSize_ = definition.lotSize();
  minPrice_ = definition.minPrice();
  maxPrice_ = definition.maxPrice();
  bandWidth_ = definition.bandWidth();
  reference_ = definition.openingReference();
  baseTick_ = (minPrice_ + tickSize_ - 1) / tickSize_;
  book_.emplace(slab_, tickSize_, baseTick_,
                static_cast<std::int32_t>(maxPrice_ / tickSize_ - baseTick_ + 1));
  feed_.instrument(definition.frame().instrumentId());
}

// Order entry -------------------------------------------------------------------------------

void LeanEngine::enter(protocol::NewOrder& newOrder) {
  const std::uint64_t clientOrderId = newOrder.clientOrderId();
  const std::uint32_t participantId = newOrder.participantId();
  const std::int32_t side = newOrder.side();
  const std::int32_t pricing = newOrder.pricing();
  const std::int32_t timeInForce = newOrder.timeInForce();
  const std::int64_t price = newOrder.price();
  const std::int64_t quantity = newOrder.quantity();

  const std::int64_t verdict = refusalOrTick(pricing, timeInForce, price, quantity);
  if (verdict < 0) {
    feed_.rejected(clientOrderId, participantId, reasonOf(verdict));
    return;
  }
  const std::uint64_t id = nextOrderId_++;
  const std::int32_t slot = slab_.acquire();
  slab_.init(slot, id, clientOrderId, participantId, side, pricing, timeInForce,
             static_cast<std::int32_t>(verdict), quantity, ++arrival_, 0);
  feed_.accepted(id, clientOrderId, participantId);
  if (state_ == CONTINUOUS) {
    match(slot, side);
  }
  settle(slot, side);
}

void LeanEngine::settle(const std::int32_t slot, const std::int32_t side) {
  if (slab_.remaining(slot) == 0) {
    slab_.release(slot);
    return;
  }
  if (slab_.pricing(slot) == LIMIT && slab_.timeInForce(slot) <= DAY) {
    slab_.rest(slot, ++arrival_);
    book_->add(side, slot);
    feed_.rested(slab_.id(slot), side, book_->priceOfTick(slab_.tick(slot)), slab_.remaining(slot));
    return;
  }
  feed_.removed(slab_.id(slot), slab_.remaining(slot), RemoveReason::IMMEDIATE_OR_CANCEL_REMAINDER);
  slab_.release(slot);
}

// Matching ----------------------------------------------------------------------------------

// (FR-3.1, FR-3.3) Best price first, then earliest arrival, until nothing crosses.
void LeanEngine::match(const std::int32_t taker, const std::int32_t side) {
  const std::int32_t limitRank = slab_.pricing(taker) == MARKET
                                     ? book_->marketLimit()
                                     : book_->rankOf(side ^ 1, slab_.tick(taker));
  while (slab_.remaining(taker) > 0) {
    const std::int32_t resting = book_->nextToTake(side, limitRank);
    if (resting == 0) {
      return;
    }
    const std::int64_t quantity = std::min(slab_.remaining(taker), slab_.remaining(resting));
    const std::int64_t price = book_->priceOfTick(slab_.tick(resting));
    slab_.take(taker, quantity);
    slab_.take(resting, quantity);
    feed_.executed(nextExecutionId_++, slab_.id(taker), slab_.id(resting), price, quantity);
    reference_ = price;
    const std::int32_t restingSide = side ^ 1;
    book_->quantityChanged(restingSide, resting, -quantity);
    if (slab_.remaining(resting) == 0) {
      book_->remove(restingSide, resting);
      slab_.release(resting);
    }
  }
}

// Amend and cancel --------------------------------------------------------------------------

void LeanEngine::cancel(const std::uint64_t clientOrderId, const std::uint32_t participantId) {
  if (state_ == CLOSED) {
    feed_.rejected(clientOrderId, participantId, RejectReason::STATE_NOT_PERMITTED);
    return;
  }
  const std::int32_t resting = book_->named(participantId, clientOrderId);
  if (resting == 0) {
    feed_.rejected(clientOrderId, participantId, RejectReason::UNKNOWN_ORDER);
    return;
  }
  book_->remove(slab_.side(resting), resting);
  feed_.removed(slab_.id(resting), slab_.remaining(resting), RemoveReason::CANCELLED);
  slab_.release(resting);
}

void LeanEngine::replace(const std::uint64_t clientOrderId, const std::uint32_t participantId,
                         const std::int64_t quantity, const std::int64_t price) {
  if (state_ == CLOSED) {
    feed_.rejected(clientOrderId, participantId, RejectReason::STATE_NOT_PERMITTED);
    return;
  }
  const std::int32_t resting = book_->named(participantId, clientOrderId);
  if (resting == 0) {
    feed_.rejected(clientOrderId, participantId, RejectReason::UNKNOWN_ORDER);
    return;
  }
  const std::int64_t verdict = refusalOrTickForReplace(resting, quantity, price);
  if (verdict < 0) {
    feed_.rejected(clientOrderId, participantId, reasonOf(verdict));
    return;
  }
  const std::int32_t side = slab_.side(resting);
  const std::int32_t newTick = static_cast<std::int32_t>(verdict);
  const std::int64_t remainder = quantity - slab_.executed(resting);
  if (newTick == slab_.tick(resting) && remainder < slab_.remaining(resting)) {
    // (FR-4.4) Lowering quantity at the same price keeps queue position.
    const std::int64_t remainingBefore = slab_.remaining(resting);
    slab_.reduceTo(resting, remainder);
    book_->quantityChanged(side, resting, remainder - remainingBefore);
    feed_.reduced(slab_.id(resting), slab_.remaining(resting));
    return;
  }
  // (FR-4.5) Anything else is a removal and a fresh rest, keeping both ids (FR-4.8).
  book_->remove(side, resting);
  feed_.removed(slab_.id(resting), slab_.remaining(resting), RemoveReason::REPLACED);
  const std::uint64_t id = slab_.id(resting);
  const std::int32_t pricing = slab_.pricing(resting);
  const std::int32_t timeInForce = slab_.timeInForce(resting);
  const std::int64_t executed = slab_.executed(resting);
  slab_.release(resting);
  const std::int32_t fresh = slab_.acquire();
  slab_.init(fresh, id, clientOrderId, participantId, side, pricing, timeInForce, newTick,
             remainder, ++arrival_, executed);
  if (state_ == CONTINUOUS) {
    match(fresh, side);
  }
  settle(fresh, side);
}

// (FR-4.7) Everything for one participant, in arrival order.
void LeanEngine::massCancel(const std::uint64_t clientOrderId, const std::uint32_t participantId) {
  if (state_ == CLOSED) {
    feed_.rejected(clientOrderId, participantId, RejectReason::STATE_NOT_PERMITTED);
    return;
  }
  gathered_.clear();
  book_->of(participantId, gathered_);
  sortByArrival(gathered_);
  for (const std::int32_t slot : gathered_) {
    book_->remove(slab_.side(slot), slot);
    feed_.removed(slab_.id(slot), slab_.remaining(slot), RemoveReason::MASS_CANCELLED);
    slab_.release(slot);
  }
}

// Earliest first, which is every tie-break and every report order in the venue.
void LeanEngine::sortByArrival(std::vector<std::int32_t>& slots) const {
  std::sort(slots.begin(), slots.end(), [this](const std::int32_t left, const std::int32_t right) {
    return slab_.arrival(left) < slab_.arrival(right);
  });
}

// Validation --------------------------------------------------------------------------------

std::int64_t LeanEngine::refusalOrTick(const std::int32_t pricing, const std::int32_t timeInForce,
                                       const std::int64_t price,
                                       const std::int64_t quantity) const {
  if (state_ == CLOSED) {
    return refusal(RejectReason::STATE_NOT_PERMITTED);
  }
  if (quantity <= 0) {
    return refusal(RejectReason::NON_POSITIVE_QUANTITY);
  }
  if (lotSize_ != 1 && quantity % lotSize_ != 0) {
    return refusal(RejectReason::LOT_VIOLATION);
  }
  if (pricing == MARKET && timeInForce <= DAY) {
    // (VR-3.1) A market order cannot rest, so it cannot be told to.
    return refusal(RejectReason::INVALID_FIELDS);
  }
  if (pricing == LIMIT) {
    return tickOrRefusal(price);
  }
  return 0;
}

// The price's tick index, or the refusal that keeps it off the ladder, in one division: the
// quotient that proves the price on tick (VR-2.2) is the index the ladder wants.
std::int64_t LeanEngine::tickOrRefusal(const std::int64_t price) const {
  if (price <= 0) {
    return refusal(RejectReason::NON_POSITIVE_PRICE);
  }
  const std::int64_t ticks = price / tickSize_;
  if (ticks * tickSize_ != price) {
    return refusal(RejectReason::TICK_VIOLATION);
  }
  if (price < minPrice_ || price > maxPrice_) {
    return refusal(RejectReason::STATIC_BAND_VIOLATION);
  }
  if (std::abs(price - reference_) > bandWidth_) {
    return refusal(RejectReason::DYNAMIC_BAND_VIOLATION);
  }
  return ticks - baseTick_;
}

std::int64_t LeanEngine::refusalOrTickForReplace(const std::int32_t resting,
                                                 const std::int64_t quantity,
                                                 const std::int64_t price) const {
  if (quantity <= 0) {
    return refusal(RejectReason::NON_POSITIVE_QUANTITY);
  }
  if (quantity <= slab_.executed(resting)) {
    // (FR-4.9) Nothing can un-trade what has traded.
    return refusal(RejectReason::QUANTITY_BELOW_EXECUTED);
  }
  if (lotSize_ != 1 && quantity % lotSize_ != 0) {
    return refusal(RejectReason::LOT_VIOLATION);
  }
  return tickOrRefusal(price);
}

}  // namespace io::github::giovanicaprison::matching::lean::flyweight

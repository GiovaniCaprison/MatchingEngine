#include "flyweight/flyweight_engine.hpp"

#include <algorithm>
#include <cstdlib>
#include <stdexcept>
#include <string>

#include "io_github_giovanicaprison_matching_protocol/AllocationAlgorithm.h"
#include "io_github_giovanicaprison_matching_protocol/CancelOrder.h"
#include "io_github_giovanicaprison_matching_protocol/MassCancel.h"
#include "io_github_giovanicaprison_matching_protocol/MessageHeader.h"
#include "io_github_giovanicaprison_matching_protocol/RejectReason.h"
#include "io_github_giovanicaprison_matching_protocol/RemoveReason.h"
#include "io_github_giovanicaprison_matching_protocol/ReplaceOrder.h"
#include "io_github_giovanicaprison_matching_protocol/SessionStateChange.h"

namespace io::github::giovanicaprison::matching::flyweight {

namespace {

using protocol::AllocationAlgorithm;
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
constexpr std::int32_t IOC = TimeInForce::IMMEDIATE_OR_CANCEL;
constexpr std::int32_t FOK = TimeInForce::FILL_OR_KILL;
constexpr std::int32_t OPENING_AUCTION = SessionState::OPENING_AUCTION;
constexpr std::int32_t CONTINUOUS = SessionState::CONTINUOUS;
constexpr std::int32_t CLOSING_AUCTION = SessionState::CLOSING_AUCTION;
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

bool callPhase(const std::int32_t state) {
  return state == OPENING_AUCTION || state == CLOSING_AUCTION;
}

// (VR-3.1) Combinations that contradict themselves: a market order told to rest, and a post-only
// order told never to rest, are each an instruction that cannot be followed.
bool inconsistent(const std::int32_t pricing, const std::int32_t timeInForce, const bool postOnly) {
  if (pricing == MARKET) {
    return postOnly || timeInForce <= DAY;
  }
  return postOnly && timeInForce >= IOC;
}

}  // namespace

void FlyweightEngine::onCommand(char* buffer, const std::size_t offset, const std::size_t length) {
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
      changeState(command.state());
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

// The definition arrives once, before every other command (FR-1.1), which is what licenses the
// ladder: the tick range is known, so the levels can be an array and this is the one allocation
// the engine's life holds after construction.
void FlyweightEngine::define(protocol::InstrumentDefinition& definition) {
  tickSize_ = definition.tickSize();
  lotSize_ = definition.lotSize();
  minPrice_ = definition.minPrice();
  maxPrice_ = definition.maxPrice();
  bandWidth_ = definition.bandWidth();
  reference_ = definition.openingReference();
  proRata_ = definition.allocation() == AllocationAlgorithm::PRO_RATA;
  baseTick_ = (minPrice_ + tickSize_ - 1) / tickSize_;
  book_.emplace(slab_, tickSize_, baseTick_,
                static_cast<std::int32_t>(maxPrice_ / tickSize_ - baseTick_ + 1));
  feed_.instrument(definition.frame().instrumentId());
}

// Order entry -------------------------------------------------------------------------------

// (FR-1.2, FR-1.3, FR-1.4)
void FlyweightEngine::enter(protocol::NewOrder& newOrder) {
  const std::uint64_t clientOrderId = newOrder.clientOrderId();
  const std::uint32_t participantId = newOrder.participantId();
  const std::int32_t side = newOrder.side();
  const std::int32_t pricing = newOrder.pricing();
  const std::int32_t timeInForce = newOrder.timeInForce();
  const bool postOnly = newOrder.flags().postOnly();
  const std::int64_t price = newOrder.price();
  const std::int64_t quantity = newOrder.quantity();
  const std::int64_t minQuantity = newOrder.minQuantity();
  const std::int64_t displayQuantity = newOrder.displayQuantity();
  const std::int64_t triggerPrice = newOrder.triggerPrice();
  const std::uint64_t smpId = newOrder.smpId();

  const std::int64_t verdict = refusalOrTick(side, pricing, timeInForce, postOnly, price, quantity,
                                             minQuantity, displayQuantity, triggerPrice, smpId);
  if (verdict < 0) {
    feed_.rejected(clientOrderId, participantId, reasonOf(verdict));
    return;
  }
  const std::uint64_t id = nextOrderId_++;
  const std::int32_t slot = slab_.acquire();
  slab_.init(slot, id, clientOrderId, participantId, side, pricing, timeInForce, postOnly,
             static_cast<std::int32_t>(verdict), quantity, minQuantity, displayQuantity,
             triggerPrice, smpId, ++arrival_, 0);
  feed_.accepted(id, clientOrderId, participantId);
  admit(slot, side);
}

void FlyweightEngine::admit(const std::int32_t slot, const std::int32_t side) {
  if (slab_.stop(slot)) {
    triggers_.add(slot);
    // (FR-6.6) A stop whose price the market has already reached is due now.
    fireTriggers();
    return;
  }
  if (state_ == CONTINUOUS) {
    match(slot, side);
    fireTriggers();
  }
  settle(slot, side);
}

void FlyweightEngine::settle(const std::int32_t slot, const std::int32_t side) {
  if (slab_.remaining(slot) == 0) {
    slab_.release(slot);
    return;
  }
  if (slab_.pricing(slot) == LIMIT && slab_.timeInForce(slot) <= DAY) {
    slab_.rest(slot, ++arrival_);
    book_->add(side, slot);
    feed_.rested(slab_.id(slot), side, book_->priceOfTick(slab_.tick(slot)), slab_.displayed(slot));
    reportIndicative();
    return;
  }
  feed_.removed(slab_.id(slot), slab_.remaining(slot), RemoveReason::IMMEDIATE_OR_CANCEL_REMAINDER);
  slab_.release(slot);
}

// Matching ----------------------------------------------------------------------------------

// (FR-3.1) Best price first, one price level at a time, until nothing crosses.
void FlyweightEngine::match(const std::int32_t taker, const std::int32_t side) {
  const std::int32_t limitRank = limitRankOf(taker, side);
  const std::uint64_t smpId = slab_.smpId(taker);
  const bool proRata = proRata_;
  while (slab_.remaining(taker) > 0) {
    const std::int32_t resting = book_->nextToTake(side, limitRank);
    if (resting == 0) {
      return;
    }
    if (prevented(smpId, resting, side)) {
      continue;
    }
    if (proRata) {
      proRataTake(taker, side, limitRank, slab_.tick(resting));
    } else {
      takeExactly(taker, side, resting, std::min(slab_.remaining(taker), slab_.displayed(resting)));
    }
  }
}

// The taker's limit as a rank in the resting side's own space; a market order reaches all.
std::int32_t FlyweightEngine::limitRankOf(const std::int32_t taker, const std::int32_t side) const {
  return slab_.pricing(taker) == MARKET ? book_->marketLimit()
                                        : book_->rankOf(side ^ 1, slab_.tick(taker));
}

// (FR-3.7) The resting order goes and the walk continues into whatever was behind it.
bool FlyweightEngine::prevented(const std::uint64_t smpId, const std::int32_t resting,
                                const std::int32_t takerSide) {
  if (smpId == 0 || smpId != slab_.smpId(resting)) {
    return false;
  }
  book_->remove(takerSide ^ 1, resting);
  feed_.removed(slab_.id(resting), slab_.displayed(resting), RemoveReason::SELF_MATCH_PREVENTED);
  slab_.release(resting);
  return true;
}

// (FR-3.5, FR-3.6) One execution, at the price the resting order named.
void FlyweightEngine::takeExactly(const std::int32_t taker, const std::int32_t side,
                                  const std::int32_t resting, const std::int64_t quantity) {
  const std::int64_t price = book_->priceOfTick(slab_.tick(resting));
  slab_.take(taker, quantity);
  const std::int64_t shownBefore = slab_.displayed(resting);
  const bool replenishes = slab_.take(resting, quantity);
  feed_.executed(nextExecutionId_++, slab_.id(taker), slab_.id(resting), price, quantity);
  reference_ = price;
  lastExecuted_ = price;
  const std::int32_t restingSide = side ^ 1;
  if (slab_.remaining(resting) == 0) {
    book_->quantitiesChanged(restingSide, resting, -shownBefore, -quantity);
    book_->remove(restingSide, resting);
    slab_.release(resting);
    return;
  }
  if (replenishes) {
    // (FR-5.4) One delta covers the take and the reveal: the level goes from holding what this
    // order showed before the execution to holding its fresh tranche, at the back of its queue.
    slab_.rest(resting, ++arrival_);
    book_->requeued(restingSide, resting, slab_.displayed(resting) - shownBefore, -quantity);
    feed_.rested(slab_.id(resting), restingSide, price, slab_.displayed(resting));
    return;
  }
  book_->quantitiesChanged(restingSide, resting, slab_.displayed(resting) - shownBefore, -quantity);
}

// (FR-3.2, FR-3.4) Pro-rata at one price: shares in proportion to displayed quantity, rounded down
// to a whole lot, and whatever rounding left over goes in arrival order. The queue is copied out
// before anything trades, because a fill unlinks and a replenish re-queues, and the allocation is
// owed to the queue as it stood.
void FlyweightEngine::proRataTake(const std::int32_t taker, const std::int32_t side,
                                  const std::int32_t limitRank, const std::int32_t tick) {
  const std::int32_t restingSide = side ^ 1;
  const std::int32_t head = book_->headAtRank(restingSide, book_->rankOf(restingSide, tick));
  if (head == 0) {
    return;
  }
  snapshot_.clear();
  std::int64_t available = 0;
  for (std::int32_t resting = head; resting != 0; resting = slab_.next(resting)) {
    snapshot_.push_back(resting);
    available += slab_.displayed(resting);
  }
  if (available == 0) {
    return;
  }
  const std::int64_t wanted = std::min(slab_.remaining(taker), available);
  for (const std::int32_t resting : snapshot_) {
    if (slab_.remaining(taker) == 0) {
      break;
    }
    const std::int64_t displayed = slab_.displayed(resting);
    const std::int64_t share = wanted * displayed / available / lotSize_ * lotSize_;
    const std::int64_t quantity = std::min(std::min(share, displayed), slab_.remaining(taker));
    if (quantity > 0) {
      takeExactly(taker, side, resting, quantity);
    }
  }
  // Rounding leaves a remainder, and arrival order decides it.
  while (slab_.remaining(taker) > 0) {
    const std::int32_t next = book_->nextToTake(side, limitRank);
    if (next == 0 || slab_.tick(next) != tick) {
      return;
    }
    takeExactly(taker, side, next, std::min(slab_.remaining(taker), slab_.displayed(next)));
  }
}

// Triggers ----------------------------------------------------------------------------------

// (FR-6.4) A cascade runs to completion before the next command is applied.
void FlyweightEngine::fireTriggers() {
  if (lastExecuted_ == 0) {
    return;
  }
  triggers_.fire(lastExecuted_, pending_);
  while (pendingNext_ < pending_.size()) {
    const std::int32_t fired = pending_[pendingNext_++];
    feed_.triggered(slab_.id(fired));
    slab_.triggered(fired, ++arrival_);
    const std::int32_t side = slab_.side(fired);
    if (state_ == CONTINUOUS) {
      match(fired, side);
    }
    settle(fired, side);
    triggers_.fire(lastExecuted_, pending_);
  }
  pending_.clear();
  pendingNext_ = 0;
}

// Amend and cancel --------------------------------------------------------------------------

// (FR-4.1, FR-4.2)
void FlyweightEngine::cancel(const std::uint64_t clientOrderId, const std::uint32_t participantId) {
  if (state_ == CLOSED) {
    feed_.rejected(clientOrderId, participantId, RejectReason::STATE_NOT_PERMITTED);
    return;
  }
  const std::int32_t resting = book_->named(participantId, clientOrderId);
  if (resting != 0) {
    book_->remove(slab_.side(resting), resting);
    feed_.removed(slab_.id(resting), slab_.displayed(resting), RemoveReason::CANCELLED);
    slab_.release(resting);
    reportIndicative();
    return;
  }
  const std::int32_t stop = triggers_.named(participantId, clientOrderId);
  if (stop != 0) {
    // (FR-6.5) A stop never appeared as resting, so what it takes with it is its whole quantity.
    triggers_.remove(stop);
    feed_.removed(slab_.id(stop), slab_.remaining(stop), RemoveReason::CANCELLED);
    slab_.release(stop);
    return;
  }
  feed_.rejected(clientOrderId, participantId, RejectReason::UNKNOWN_ORDER);
}

// (FR-4.3, FR-4.4, FR-4.5, FR-4.6, FR-4.8)
void FlyweightEngine::replace(const std::uint64_t clientOrderId, const std::uint32_t participantId,
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
    // (FR-4.4, FR-8.5) Lowering quantity at the same price keeps queue position.
    const std::int64_t shownBefore = slab_.displayed(resting);
    const std::int64_t remainingBefore = slab_.remaining(resting);
    slab_.reduceTo(resting, remainder);
    book_->quantitiesChanged(side, resting, slab_.displayed(resting) - shownBefore,
                             remainder - remainingBefore);
    feed_.reduced(slab_.id(resting), slab_.displayed(resting));
    reportIndicative();
    return;
  }
  // (FR-4.5) Anything else is a removal and a fresh rest, keeping both ids (FR-4.8) and the
  // display size it was entered with (FR-4.10), in a fresh slot so the old one can go back.
  book_->remove(side, resting);
  feed_.removed(slab_.id(resting), slab_.displayed(resting), RemoveReason::REPLACED);
  const std::uint64_t id = slab_.id(resting);
  const std::int32_t pricing = slab_.pricing(resting);
  const std::int32_t timeInForce = slab_.timeInForce(resting);
  const bool postOnly = slab_.postOnly(resting);
  const std::int64_t minQuantity = slab_.minQuantity(resting);
  const std::int64_t displaySize = slab_.displaySize(resting);
  const std::uint64_t smpId = slab_.smpId(resting);
  const std::int64_t executed = slab_.executed(resting);
  slab_.release(resting);
  const std::int32_t fresh = slab_.acquire();
  slab_.init(fresh, id, clientOrderId, participantId, side, pricing, timeInForce, postOnly, newTick,
             remainder, minQuantity, displaySize, 0, smpId, ++arrival_, executed);
  admit(fresh, side);
}

// (FR-4.7) Everything for one participant, in arrival order, book and stops alike.
void FlyweightEngine::massCancel(const std::uint64_t clientOrderId,
                                 const std::uint32_t participantId) {
  if (state_ == CLOSED) {
    feed_.rejected(clientOrderId, participantId, RejectReason::STATE_NOT_PERMITTED);
    return;
  }
  gathered_.clear();
  book_->of(participantId, gathered_);
  triggers_.of(participantId, gathered_);
  sortByArrival(gathered_);
  for (const std::int32_t slot : gathered_) {
    if (slab_.stop(slot)) {
      triggers_.remove(slot);
      feed_.removed(slab_.id(slot), slab_.remaining(slot), RemoveReason::MASS_CANCELLED);
    } else {
      book_->remove(slab_.side(slot), slot);
      feed_.removed(slab_.id(slot), slab_.displayed(slot), RemoveReason::MASS_CANCELLED);
    }
    slab_.release(slot);
  }
  reportIndicative();
}

// Trading state -----------------------------------------------------------------------------

// (FR-7.1, FR-7.2, FR-7.8) The state moves on a command and on nothing else.
void FlyweightEngine::changeState(const std::int32_t entering) {
  if (callPhase(state_) && entering != state_) {
    // (FR-7.5, FR-7.6, FR-7.10) Leaving a call phase is what runs the uncrossing.
    uncross();
  }
  state_ = entering;
  feed_.stateChanged(static_cast<SessionState::Value>(state_));
  indicativePrice_ = 0;
  indicativeQuantity_ = 0;
  if (callPhase(state_)) {
    reportIndicative();
  }
}

void FlyweightEngine::uncross() {
  auction_.uncross(*book_, reference_);
  if (!auction_.crosses()) {
    return;
  }
  const std::int64_t price = auction_.price();
  std::int64_t left = auction_.quantity();
  willing(0, price, buys_);
  willing(1, price, sells_);
  std::size_t sell = 0;
  for (const std::int32_t buy : buys_) {
    // A filled order's slot goes back to the free list inside cross, and nothing reacquires it
    // before these reads, so its quantities still say what they said when it died.
    while (slab_.remaining(buy) > 0 && left > 0 && sell < sells_.size()) {
      const std::int32_t resting = sells_[sell];
      left -= cross(buy, resting, price, left);
      if (slab_.remaining(resting) == 0) {
        sell++;
      }
    }
  }
  reference_ = price;
  lastExecuted_ = price;
  fireTriggers();
}

// (FR-7.6) One execution inside an auction, at the one price the auction found.
std::int64_t FlyweightEngine::cross(const std::int32_t buy, const std::int32_t sell,
                                    const std::int64_t price, const std::int64_t left) {
  const std::int64_t quantity =
      std::min(std::min(slab_.displayed(buy), slab_.displayed(sell)), left);
  const std::int64_t buyShown = slab_.displayed(buy);
  const std::int64_t sellShown = slab_.displayed(sell);
  const bool buyReplenishes = slab_.take(buy, quantity);
  const bool sellReplenishes = slab_.take(sell, quantity);
  feed_.executed(nextExecutionId_++, slab_.id(buy), slab_.id(sell), price, quantity);
  reveal(buy, 0, buyReplenishes, buyShown, quantity);
  reveal(sell, 1, sellReplenishes, sellShown, quantity);
  return quantity;
}

// Hidden quantity is displayed before it executes, in an auction as elsewhere (FR-5.5).
void FlyweightEngine::reveal(const std::int32_t slot, const std::int32_t side,
                             const bool replenishes, const std::int64_t shownBefore,
                             const std::int64_t quantity) {
  if (slab_.remaining(slot) == 0) {
    book_->quantitiesChanged(side, slot, -shownBefore, -quantity);
    book_->remove(side, slot);
    slab_.release(slot);
  } else if (replenishes) {
    slab_.rest(slot, ++arrival_);
    book_->requeued(side, slot, slab_.displayed(slot) - shownBefore, -quantity);
    feed_.rested(slab_.id(slot), side, book_->priceOfTick(slab_.tick(slot)), slab_.displayed(slot));
  } else {
    book_->quantitiesChanged(side, slot, slab_.displayed(slot) - shownBefore, -quantity);
  }
}

// Everyone on one side willing at the price, earliest first, into the caller's space.
void FlyweightEngine::willing(const std::int32_t side, const std::int64_t price,
                              std::vector<std::int32_t>& into) {
  into.clear();
  const std::int32_t limit = book_->willingLimitRank(side, price);
  for (std::int32_t rank = book_->firstRank(side); rank <= limit;
       rank = book_->rankAfter(side, rank)) {
    for (std::int32_t slot = book_->headAtRank(side, rank); slot != 0; slot = slab_.next(slot)) {
      into.push_back(slot);
    }
  }
  sortByArrival(into);
}

// (FR-7.7) Reported whenever it changes, and only while there is an auction to report on.
void FlyweightEngine::reportIndicative() {
  if (!callPhase(state_)) {
    return;
  }
  auction_.uncross(*book_, reference_);
  if (auction_.price() == indicativePrice_ && auction_.quantity() == indicativeQuantity_) {
    return;
  }
  indicativePrice_ = auction_.price();
  indicativeQuantity_ = auction_.quantity();
  feed_.indicative(indicativePrice_, indicativeQuantity_);
}

// Earliest first, which is every tie-break and every report order in the venue.
void FlyweightEngine::sortByArrival(std::vector<std::int32_t>& slots) const {
  std::sort(slots.begin(), slots.end(), [this](const std::int32_t left, const std::int32_t right) {
    return slab_.arrival(left) < slab_.arrival(right);
  });
}

// Validation --------------------------------------------------------------------------------

std::int64_t FlyweightEngine::refusalOrTick(const std::int32_t side, const std::int32_t pricing,
                                            const std::int32_t timeInForce, const bool postOnly,
                                            const std::int64_t price, const std::int64_t quantity,
                                            const std::int64_t minQuantity,
                                            const std::int64_t displayQuantity,
                                            const std::int64_t triggerPrice,
                                            const std::uint64_t smpId) const {
  if (state_ == CLOSED) {
    return refusal(RejectReason::STATE_NOT_PERMITTED);
  }
  if (quantity <= 0) {
    return refusal(RejectReason::NON_POSITIVE_QUANTITY);
  }
  if (lotSize_ != 1 && quantity % lotSize_ != 0) {
    return refusal(RejectReason::LOT_VIOLATION);
  }
  if (minQuantity > quantity) {
    return refusal(RejectReason::MINIMUM_QUANTITY_ABOVE_ORDER);
  }
  if (displayQuantity > quantity) {
    return refusal(RejectReason::DISPLAY_QUANTITY_ABOVE_ORDER);
  }
  if (inconsistent(pricing, timeInForce, postOnly)) {
    return refusal(RejectReason::INVALID_FIELDS);
  }
  std::int64_t tick = 0;
  if (pricing == LIMIT) {
    tick = tickOrRefusal(price);
    if (tick < 0) {
      return tick;
    }
    if (std::abs(price - reference_) > bandWidth_) {
      return refusal(RejectReason::DYNAMIC_BAND_VIOLATION);
    }
  }
  if (triggerPrice != 0) {
    // A stop is placed away from where the market is, so the dynamic band does not apply.
    const std::int64_t triggerTick = tickOrRefusal(triggerPrice);
    if (triggerTick < 0) {
      return triggerTick;
    }
  }
  const std::int32_t fromTheBook =
      refusalFromTheBook(side, pricing, timeInForce, postOnly, quantity, minQuantity, smpId,
                         triggerPrice, static_cast<std::int32_t>(tick));
  return fromTheBook >= 0 ? refusal(static_cast<RejectReason::Value>(fromTheBook)) : tick;
}

// The price's tick index, or the refusal that keeps it off the ladder, in one division: the
// quotient that proves the price on tick (VR-2.2) is the index the ladder wants.
std::int64_t FlyweightEngine::tickOrRefusal(const std::int64_t price) const {
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
  return ticks - baseTick_;
}

// (FR-2.4, FR-2.5, FR-2.6) The refusals that have to ask the book first. Negative means nothing
// is wrong, so the reason values, which start at zero, pass through undisturbed.
std::int32_t FlyweightEngine::refusalFromTheBook(
    const std::int32_t side, const std::int32_t pricing, const std::int32_t timeInForce,
    const bool postOnly, const std::int64_t quantity, const std::int64_t minQuantity,
    const std::uint64_t smpId, const std::int64_t triggerPrice, const std::int32_t tick) const {
  if (triggerPrice != 0 || state_ != CONTINUOUS) {
    if (triggerPrice == 0 && timeInForce == FOK) {
      return RejectReason::FILL_OR_KILL_UNFILLABLE;
    }
    if (triggerPrice == 0 && minQuantity > 0) {
      return RejectReason::MINIMUM_QUANTITY_NOT_MET;
    }
    return -1;
  }
  const std::int32_t limitRank =
      pricing == MARKET ? book_->marketLimit() : book_->rankOf(side ^ 1, tick);
  if (postOnly && book_->nextToTake(side, limitRank) != 0) {
    return RejectReason::WOULD_CROSS;
  }
  if (timeInForce == FOK || minQuantity > 0) {
    const std::int64_t fillable = book_->fillable(side, limitRank, smpId);
    if (timeInForce == FOK && fillable < quantity) {
      return RejectReason::FILL_OR_KILL_UNFILLABLE;
    }
    if (minQuantity > 0 && fillable < minQuantity) {
      return RejectReason::MINIMUM_QUANTITY_NOT_MET;
    }
  }
  return -1;
}

std::int64_t FlyweightEngine::refusalOrTickForReplace(const std::int32_t resting,
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
  const std::int64_t tick = tickOrRefusal(price);
  if (tick < 0) {
    return tick;
  }
  if (std::abs(price - reference_) > bandWidth_) {
    return refusal(RejectReason::DYNAMIC_BAND_VIOLATION);
  }
  const std::int32_t side = slab_.side(resting);
  if (slab_.postOnly(resting) && state_ == CONTINUOUS &&
      book_->nextToTake(side, book_->rankOf(side ^ 1, static_cast<std::int32_t>(tick))) != 0) {
    // (FR-4.6) A replace refused by a liquidity flag leaves the original order resting.
    return refusal(RejectReason::WOULD_CROSS);
  }
  return tick;
}

}  // namespace io::github::giovanicaprison::matching::flyweight

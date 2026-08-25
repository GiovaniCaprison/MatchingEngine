#include "naive/naive_engine.hpp"

#include <algorithm>
#include <cstdlib>
#include <deque>
#include <stdexcept>
#include <string>

#include "io_github_giovanicaprison_matching_protocol/CancelOrder.h"
#include "io_github_giovanicaprison_matching_protocol/InstrumentDefinition.h"
#include "io_github_giovanicaprison_matching_protocol/MassCancel.h"
#include "io_github_giovanicaprison_matching_protocol/MessageHeader.h"
#include "io_github_giovanicaprison_matching_protocol/ReplaceOrder.h"
#include "io_github_giovanicaprison_matching_protocol/SessionStateChange.h"
#include "naive/auction.hpp"

namespace io::github::giovanicaprison::matching::naive {

namespace {

using protocol::AllocationAlgorithm;
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

bool callPhase(const SessionState::Value state) {
  return state == SessionState::OPENING_AUCTION || state == SessionState::CLOSING_AUCTION;
}

// (VR-3.1) Combinations that contradict themselves: a market order with a price of its own, one
// that is told to rest, and one told never to take are each an instruction that cannot be
// followed. A display quantity is not on the list: an order that never rests displays nothing,
// which is what it already does.
bool inconsistent(const PricingInstruction::Value pricing, const TimeInForce::Value timeInForce,
                  const bool postOnly) {
  if (pricing == PricingInstruction::MARKET) {
    return postOnly || timeInForce == TimeInForce::GOOD_TILL_CANCEL ||
           timeInForce == TimeInForce::DAY;
  }
  return postOnly && (timeInForce == TimeInForce::IMMEDIATE_OR_CANCEL ||
                      timeInForce == TimeInForce::FILL_OR_KILL);
}

}  // namespace

void NaiveEngine::onCommand(char* buffer, const std::size_t offset, const std::size_t length) {
  MessageHeader header;
  const std::size_t end = offset + length;
  header.wrap(buffer, offset, 0, end);
  const std::size_t body = offset + MessageHeader::encodedLength();
  switch (header.templateId()) {
    case protocol::InstrumentDefinition::sbeTemplateId(): {
      // (FR-1.1) The instrument arrives once and configures everything after it.
      auto definition = decoded<protocol::InstrumentDefinition>(buffer, body, header, end);
      instrument_ = Instrument::of(definition);
      reference_ = instrument_.openingReference;
      feed_.instrument(instrument_.id);
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
      changeState(command.state());
      return;
    }
    default:
      throw std::invalid_argument("template " + std::to_string(header.templateId()) +
                                  " is not a command (P-14)");
  }
}

// Order entry -----------------------------------------------------------------------------------

// (FR-1.2, FR-1.3, FR-1.4)
void NaiveEngine::enter(protocol::NewOrder& newOrder) {
  const std::uint64_t clientOrderId = newOrder.clientOrderId();
  const std::uint32_t participantId = newOrder.participantId();
  const RejectReason::Value refusal = refusalFor(newOrder);
  if (refusal != NOTHING_WRONG) {
    feed_.rejected(clientOrderId, participantId, refusal);
    return;
  }
  const OrderPtr order = std::make_shared<Order>(
      nextOrderId_++, clientOrderId, participantId, newOrder.side(), newOrder.pricing(),
      newOrder.timeInForce(), newOrder.flags().postOnly(), newOrder.price(), newOrder.quantity(),
      newOrder.minQuantity(), newOrder.displayQuantity(), newOrder.triggerPrice(), newOrder.smpId(),
      ++arrival_);
  feed_.accepted(*order);
  admit(order);
}

// Places an admitted order: into the trigger book, into the book, or across it. Shared by order
// entry and by the two other ways an order arrives at the book, a stop that has fired and a
// replace that lost its queue position.
void NaiveEngine::admit(const OrderPtr& order) {
  if (order->stop()) {
    triggers_.add(order);
    // (FR-6.6) A stop whose price the market has already reached is due now. Left waiting it
    // would fire on whatever executes next, at a price that has nothing to do with its condition,
    // or never fire at all if the market does not come back.
    fireTriggers();
    return;
  }
  if (matching()) {
    match(order);
    fireTriggers();
  }
  settle(order);
}

// What becomes of whatever the walk left: the book, or a removal.
void NaiveEngine::settle(const OrderPtr& order) {
  if (order->remaining() == 0) {
    return;
  }
  if (order->restsOnRemainder()) {
    order->rest(++arrival_);
    book_.add(order);
    feed_.rested(*order);
    reportIndicative();
    return;
  }
  feed_.removed(order->id(), order->remaining(), RemoveReason::IMMEDIATE_OR_CANCEL_REMAINDER);
}

// Matching --------------------------------------------------------------------------------------

// (FR-3.1) Best price first, one price level at a time, until nothing crosses.
void NaiveEngine::match(const OrderPtr& taker) {
  while (taker->remaining() > 0) {
    const OrderPtr next = book_.nextToTake(taker->side(), limitOf(*taker));
    if (next == nullptr) {
      return;
    }
    if (prevented(taker, next)) {
      continue;
    }
    if (instrument_.allocation == AllocationAlgorithm::PRO_RATA) {
      proRata(taker, next->price());
    } else {
      take(taker, next);
    }
  }
}

// (FR-3.7) The resting order goes and the walk continues into whatever was behind it.
bool NaiveEngine::prevented(const OrderPtr& taker, const OrderPtr& resting) {
  if (taker->smpId() == 0 || taker->smpId() != resting->smpId()) {
    return false;
  }
  book_.remove(resting);
  feed_.removed(resting->id(), resting->displayed(), RemoveReason::SELF_MATCH_PREVENTED);
  return true;
}

// As much as the front of the queue can give, which is what price-time allocation is.
void NaiveEngine::take(const OrderPtr& taker, const OrderPtr& resting) {
  takeExactly(taker, resting, std::min(taker->remaining(), resting->displayed()));
}

// (FR-3.5, FR-3.6) One execution, at the price the resting order named.
void NaiveEngine::takeExactly(const OrderPtr& taker, const OrderPtr& resting,
                              const std::int64_t quantity) {
  const std::int64_t price = resting->price();
  taker->take(quantity);
  const bool replenishes = resting->take(quantity);
  feed_.executed(nextExecutionId_++, taker->id(), resting->id(), price, quantity);
  reference_ = price;
  lastExecuted_ = price;
  if (resting->remaining() == 0) {
    // A resting order executed in full gets no removal event: a consumer tracking quantity has
    // already seen it reach zero.
    book_.remove(resting);
    return;
  }
  if (replenishes) {
    // (FR-5.4) The next tranche joins the back of the queue at its price, which to a consumer is
    // indistinguishable from a new order arriving there. That is what an iceberg is for.
    resting->rest(++arrival_);
    feed_.rested(*resting);
  }
}

// (FR-3.2, FR-3.4) Pro-rata at one price: shares in proportion to resting quantity, rounded down
// to a whole lot, and whatever rounding left over goes in arrival order.
void NaiveEngine::proRata(const OrderPtr& taker, const std::int64_t price) {
  const std::vector<OrderPtr> level = book_.atPrice(Book::opposite(taker->side()), price);
  std::int64_t available = 0;
  for (const OrderPtr& resting : level) {
    available += resting->displayed();
  }
  if (available == 0) {
    return;
  }
  const std::int64_t wanted = std::min(taker->remaining(), available);
  const std::int64_t lot = instrument_.lotSize;
  for (const OrderPtr& resting : level) {
    if (taker->remaining() == 0) {
      break;
    }
    const std::int64_t share = wanted * resting->displayed() / available / lot * lot;
    const std::int64_t quantity =
        std::min(std::min(share, resting->displayed()), taker->remaining());
    if (quantity > 0) {
      takeExactly(taker, resting, quantity);
    }
  }
  // Rounding leaves a remainder, and arrival order decides it. The same walk serves, since it
  // takes as much as the front of the queue can give.
  while (taker->remaining() > 0) {
    const OrderPtr next = book_.nextToTake(taker->side(), limitOf(*taker));
    if (next == nullptr || next->price() != price) {
      return;
    }
    take(taker, next);
  }
}

// Triggers --------------------------------------------------------------------------------------

// (FR-6.4) A cascade runs to completion before the next command is applied. Evaluated once the
// walk is over rather than between its executions, which is the same answer: prices in a walk move
// away from the touch monotonically, so the last executed price is the furthest one reached.
void NaiveEngine::fireTriggers() {
  if (lastExecuted_ == 0) {
    // Nothing has executed, so there is no last executed price and no stop can have been reached.
    // The band's reference price stands in for one when validating a price, and it is not one: a
    // stop asks what the market has done, not where it was told to start.
    return;
  }
  std::deque<OrderPtr> pending;
  for (const OrderPtr& fired : triggers_.fire(lastExecuted_)) {
    pending.push_back(fired);
  }
  while (!pending.empty()) {
    const OrderPtr fired = pending.front();
    pending.pop_front();
    feed_.triggered(*fired);
    const OrderPtr order = fired->triggered(++arrival_);
    if (matching()) {
      match(order);
    }
    settle(order);
    for (const OrderPtr& next : triggers_.fire(lastExecuted_)) {
      pending.push_back(next);
    }
  }
}

// Amend and cancel ------------------------------------------------------------------------------

// (FR-4.1, FR-4.2)
void NaiveEngine::cancel(const std::uint64_t clientOrderId, const std::uint32_t participantId) {
  if (state_ == SessionState::CLOSED) {
    feed_.rejected(clientOrderId, participantId, RejectReason::STATE_NOT_PERMITTED);
    return;
  }
  const OrderPtr resting = book_.named(participantId, clientOrderId);
  if (resting != nullptr) {
    book_.remove(resting);
    feed_.removed(resting->id(), resting->displayed(), RemoveReason::CANCELLED);
    reportIndicative();
    return;
  }
  const OrderPtr stop = triggers_.named(participantId, clientOrderId);
  if (stop != nullptr) {
    // (FR-6.5) A stop is reported on cancellation as well, and it never appeared as resting, so
    // what it takes with it is its whole quantity.
    triggers_.remove(stop);
    feed_.removed(stop->id(), stop->remaining(), RemoveReason::CANCELLED);
    return;
  }
  feed_.rejected(clientOrderId, participantId, RejectReason::UNKNOWN_ORDER);
}

// (FR-4.3, FR-4.4, FR-4.5, FR-4.6, FR-4.8)
void NaiveEngine::replace(const std::uint64_t clientOrderId, const std::uint32_t participantId,
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
  // The command names the order's whole quantity, so what should still be working is that less
  // whatever has already traded (FR-4.3).
  const std::int64_t remainder = quantity - resting->executed();
  if (price == resting->price() && remainder < resting->remaining()) {
    // (FR-4.4, FR-8.5) Less at the same price keeps its place, so nothing leaves the book.
    resting->reduceTo(remainder);
    feed_.reduced(*resting);
    reportIndicative();
    return;
  }
  // (FR-4.5) Anything else is a removal and a fresh rest, and the id survives both (FR-4.8). The
  // replacement keeps its display size rather than whatever tranche happened to be showing
  // (FR-4.10), and carries what it has already executed, since a later replace works its
  // remainder out from that.
  book_.remove(resting);
  feed_.replaced(*resting, resting->displayed());
  admit(std::make_shared<Order>(resting->id(), resting->clientOrderId(), resting->participantId(),
                                resting->side(), resting->pricing(), resting->timeInForce(),
                                resting->postOnly(), price, remainder, resting->minQuantity(),
                                resting->displaySize(), 0, resting->smpId(), ++arrival_,
                                resting->executed()));
}

// (FR-4.7) Everything for one participant, in arrival order, book and stops alike.
void NaiveEngine::massCancel(const std::uint64_t clientOrderId, const std::uint32_t participantId) {
  if (state_ == SessionState::CLOSED) {
    feed_.rejected(clientOrderId, participantId, RejectReason::STATE_NOT_PERMITTED);
    return;
  }
  std::vector<OrderPtr> everything = book_.of(participantId);
  const std::vector<OrderPtr> stops = triggers_.of(participantId);
  everything.insert(everything.end(), stops.begin(), stops.end());
  std::sort(everything.begin(), everything.end(), byArrival);
  for (const OrderPtr& order : everything) {
    if (order->stop()) {
      triggers_.remove(order);
      feed_.removed(order->id(), order->remaining(), RemoveReason::MASS_CANCELLED);
    } else {
      book_.remove(order);
      feed_.removed(order->id(), order->displayed(), RemoveReason::MASS_CANCELLED);
    }
  }
  reportIndicative();
}

// Trading state ---------------------------------------------------------------------------------

// (FR-7.1, FR-7.2, FR-7.8) The state moves on a command and on nothing else.
void NaiveEngine::changeState(const SessionState::Value entering) {
  if (callPhase(state_) && entering != state_) {
    // (FR-7.5, FR-7.6) Leaving a call phase is what runs the uncrossing, and its executions are
    // published before the state they belong to is left.
    uncross();
  }
  state_ = entering;
  feed_.stateChanged(state_);
  indicativePrice_ = 0;
  indicativeQuantity_ = 0;
  if (callPhase(state_)) {
    reportIndicative();
  }
}

void NaiveEngine::uncross() {
  const Auction::Uncrossing uncrossing = Auction::uncrossing(book_, reference_);
  if (!uncrossing.crosses()) {
    return;
  }
  const std::int64_t price = uncrossing.price;
  std::int64_t left = uncrossing.quantity;
  const std::vector<OrderPtr> buys = willing(protocol::Side::BUY, price);
  const std::vector<OrderPtr> sells = willing(protocol::Side::SELL, price);
  std::size_t sell = 0;
  for (const OrderPtr& buy : buys) {
    while (buy->remaining() > 0 && left > 0 && sell < sells.size()) {
      const OrderPtr& resting = sells[sell];
      left -= cross(buy, resting, price, left);
      if (resting->remaining() == 0) {
        sell++;
      }
    }
  }
  reference_ = price;
  lastExecuted_ = price;
  fireTriggers();
}

// (FR-7.6) One execution inside an auction, at the one price the auction found. Bounded by what
// both sides are showing, because hidden quantity is revealed before it trades here exactly as it
// is in continuous trading (FR-5.5). Both sides can replenish, which is what separates this from
// continuous trading: neither of them aggressed, so both are in the book.
std::int64_t NaiveEngine::cross(const OrderPtr& buy, const OrderPtr& sell, const std::int64_t price,
                                const std::int64_t left) {
  const std::int64_t quantity = std::min(std::min(buy->displayed(), sell->displayed()), left);
  const bool buyReplenishes = buy->take(quantity);
  const bool sellReplenishes = sell->take(quantity);
  feed_.executed(nextExecutionId_++, buy->id(), sell->id(), price, quantity);
  reveal(buy, buyReplenishes);
  reveal(sell, sellReplenishes);
  return quantity;
}

void NaiveEngine::reveal(const OrderPtr& order, const bool replenishes) {
  if (order->remaining() == 0) {
    book_.remove(order);
  } else if (replenishes) {
    order->rest(++arrival_);
    feed_.rested(*order);
  }
}

// Everyone who would trade at a price, earliest first.
std::vector<OrderPtr> NaiveEngine::willing(const Side side, const std::int64_t price) const {
  std::vector<OrderPtr> found;
  for (const OrderPtr& order : book_.orders()) {
    if (order->side() == side && order->willingAt(price)) {
      found.push_back(order);
    }
  }
  std::sort(found.begin(), found.end(), byArrival);
  return found;
}

// (FR-7.7) Reported whenever it changes, and only while there is an auction to report on.
void NaiveEngine::reportIndicative() {
  if (!callPhase(state_)) {
    return;
  }
  const Auction::Uncrossing uncrossing = Auction::uncrossing(book_, reference_);
  if (uncrossing.price == indicativePrice_ && uncrossing.quantity == indicativeQuantity_) {
    return;
  }
  indicativePrice_ = uncrossing.price;
  indicativeQuantity_ = uncrossing.quantity;
  feed_.indicative(indicativePrice_, indicativeQuantity_);
}

// Validation ------------------------------------------------------------------------------------

// Everything that can refuse an order, in one place and before any of it is applied. The three
// checks that need the book come last, because they are the expensive ones and because a malformed
// order should not be scanning anything.
RejectReason::Value NaiveEngine::refusalFor(protocol::NewOrder& newOrder) const {
  if (state_ == SessionState::CLOSED) {
    return RejectReason::STATE_NOT_PERMITTED;
  }
  const std::int64_t quantity = newOrder.quantity();
  if (quantity <= 0) {
    return RejectReason::NON_POSITIVE_QUANTITY;
  }
  if (quantity % instrument_.lotSize != 0) {
    return RejectReason::LOT_VIOLATION;
  }
  if (newOrder.minQuantity() > quantity) {
    return RejectReason::MINIMUM_QUANTITY_ABOVE_ORDER;
  }
  if (newOrder.displayQuantity() > quantity) {
    return RejectReason::DISPLAY_QUANTITY_ABOVE_ORDER;
  }
  const PricingInstruction::Value pricing = newOrder.pricing();
  if (inconsistent(pricing, newOrder.timeInForce(), newOrder.flags().postOnly())) {
    return RejectReason::INVALID_FIELDS;
  }
  if (pricing == PricingInstruction::LIMIT) {
    const RejectReason::Value price = refusalForPrice(newOrder.price());
    if (price != NOTHING_WRONG) {
      return price;
    }
  }
  if (newOrder.triggerPrice() != 0) {
    const RejectReason::Value trigger = refusalForTriggerPrice(newOrder.triggerPrice());
    if (trigger != NOTHING_WRONG) {
      return trigger;
    }
  }
  return refusalFromTheBook(newOrder.side(), pricing, newOrder.timeInForce(),
                            newOrder.flags().postOnly(), newOrder.price(), quantity,
                            newOrder.minQuantity(), newOrder.smpId(), newOrder.triggerPrice());
}

RejectReason::Value NaiveEngine::refusalForPrice(const std::int64_t price) const {
  const RejectReason::Value onTheInstrument = refusalOnTheInstrument(price);
  if (onTheInstrument != NOTHING_WRONG) {
    return onTheInstrument;
  }
  if (std::abs(price - reference_) > instrument_.bandWidth) {
    return RejectReason::DYNAMIC_BAND_VIOLATION;
  }
  return NOTHING_WRONG;
}

// A trigger price is a price on the instrument, so tick and bounds apply to it. The dynamic band
// does not: a stop is placed away from where the market is, which is the whole reason for having
// one, and banding it against the last executed price would refuse the stops anybody sends.
RejectReason::Value NaiveEngine::refusalForTriggerPrice(const std::int64_t price) const {
  return refusalOnTheInstrument(price);
}

// What any price on this instrument has to satisfy, trigger prices included.
RejectReason::Value NaiveEngine::refusalOnTheInstrument(const std::int64_t price) const {
  if (price <= 0) {
    return RejectReason::NON_POSITIVE_PRICE;
  }
  if (price % instrument_.tickSize != 0) {
    return RejectReason::TICK_VIOLATION;
  }
  if (price < instrument_.minPrice || price > instrument_.maxPrice) {
    return RejectReason::STATIC_BAND_VIOLATION;
  }
  return NOTHING_WRONG;
}

// (FR-2.5, FR-2.4, FR-2.6) The three refusals that have to ask the book first. All three are
// decided before anything is touched, which is why they are refusals and not removals: nothing
// was executed and nothing rested.
RejectReason::Value NaiveEngine::refusalFromTheBook(
    const Side side, const PricingInstruction::Value pricing, const TimeInForce::Value timeInForce,
    const bool postOnly, const std::int64_t price, const std::int64_t quantity,
    const std::int64_t minQuantity, const std::uint64_t smpId,
    const std::int64_t triggerPrice) const {
  if (triggerPrice != 0 || !matching()) {
    // A stop is not going near the book yet, and outside continuous trading nothing executes on
    // entry, so a fill-or-kill or a minimum quantity cannot be satisfied.
    if (triggerPrice == 0 && timeInForce == TimeInForce::FILL_OR_KILL) {
      return RejectReason::FILL_OR_KILL_UNFILLABLE;
    }
    if (triggerPrice == 0 && minQuantity > 0) {
      return RejectReason::MINIMUM_QUANTITY_NOT_MET;
    }
    return NOTHING_WRONG;
  }
  const std::int64_t limit = pricing == PricingInstruction::MARKET ? 0 : price;
  if (postOnly && book_.nextToTake(side, limit) != nullptr) {
    return RejectReason::WOULD_CROSS;
  }
  if (timeInForce == TimeInForce::FILL_OR_KILL || minQuantity > 0) {
    const std::int64_t fillable = book_.fillable(side, limit, smpId);
    if (timeInForce == TimeInForce::FILL_OR_KILL && fillable < quantity) {
      return RejectReason::FILL_OR_KILL_UNFILLABLE;
    }
    if (minQuantity > 0 && fillable < minQuantity) {
      return RejectReason::MINIMUM_QUANTITY_NOT_MET;
    }
  }
  return NOTHING_WRONG;
}

// (FR-4.6) A replace a liquidity flag refuses leaves the original where it was.
RejectReason::Value NaiveEngine::refusalForReplace(const Order& resting,
                                                   const std::int64_t quantity,
                                                   const std::int64_t price) const {
  if (quantity <= 0) {
    return RejectReason::NON_POSITIVE_QUANTITY;
  }
  if (quantity <= resting.executed()) {
    // (FR-4.9) Nothing can un-trade what has traded, so an order cannot be shrunk to less than it
    // has already done. Equal is refused too: that order is finished and there is nothing to
    // work.
    return RejectReason::QUANTITY_BELOW_EXECUTED;
  }
  if (quantity % instrument_.lotSize != 0) {
    return RejectReason::LOT_VIOLATION;
  }
  const RejectReason::Value refusal = refusalForPrice(price);
  if (refusal != NOTHING_WRONG) {
    return refusal;
  }
  if (resting.postOnly() && matching() && book_.nextToTake(resting.side(), price) != nullptr) {
    return RejectReason::WOULD_CROSS;
  }
  return NOTHING_WRONG;
}

}  // namespace io::github::giovanicaprison::matching::naive

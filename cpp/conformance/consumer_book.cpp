#include "conformance/consumer_book.hpp"

#include <algorithm>

namespace io::github::giovanicaprison::matching::conformance {

namespace {
namespace protocol = io::github::giovanicaprison::matching::protocol;
}

void ConsumerBook::accepted(const std::uint64_t orderId) { accepted_.insert(orderId); }

void ConsumerBook::rested(const std::uint64_t orderId, const protocol::Side::Value side,
                          const std::int64_t price, const std::int64_t quantity) {
  if (!accepted_.contains(orderId)) {
    problem(std::to_string(orderId) + " rested without having been accepted");
    return;
  }
  if (resting(orderId) != nullptr) {
    problem(std::to_string(orderId) + " rested while it was already resting");
    return;
  }
  if (quantity <= 0) {
    problem(std::to_string(orderId) + " rested with nothing showing");
    return;
  }
  entries_.push_back(Entry{orderId, side, price, quantity});
  everRested_.insert(orderId);
}

// An execution names two orders, and whichever of them this book holds loses the quantity. One
// rule for both regimes: in continuous trading only the resting side is here, in an auction
// neither side aggressed and both are, so both are decremented.
void ConsumerBook::executed(const std::uint64_t aggressorOrderId,
                            const std::uint64_t restingOrderId, const std::int64_t price,
                            const std::int64_t quantity) {
  if (resting(restingOrderId) == nullptr && resting(aggressorOrderId) == nullptr) {
    problem("executed between " + std::to_string(aggressorOrderId) + " and " +
            std::to_string(restingOrderId) + ", neither of them resting");
    return;
  }
  take(restingOrderId, price, quantity);
  take(aggressorOrderId, price, quantity);
}

void ConsumerBook::take(const std::uint64_t orderId, const std::int64_t price,
                        const std::int64_t quantity) {
  Entry* held = resting(orderId);
  if (held == nullptr) {
    return;
  }
  // Never worse than the order's own limit, which holds at a resting price and at an uncrossing
  // one.
  const bool worse = held->side == protocol::Side::BUY ? price > held->price : price < held->price;
  if (worse) {
    problem(std::to_string(orderId) + " executed at " + std::to_string(price) +
            " having asked for " + std::to_string(held->price));
    return;
  }
  if (quantity > held->quantity) {
    problem(std::to_string(orderId) + " executed " + std::to_string(quantity) + " with " +
            std::to_string(held->quantity) + " showing");
    return;
  }
  held->quantity -= quantity;
  if (held->quantity == 0) {
    // An order executed in full gets no removal. The consumer has seen it reach zero.
    erase(orderId);
  }
}

void ConsumerBook::reduced(const std::uint64_t orderId, const std::int64_t quantity) {
  Entry* held = resting(orderId);
  if (held == nullptr) {
    problem(std::to_string(orderId) + " was reduced while it was not resting");
    return;
  }
  if (quantity <= 0 || quantity > held->quantity) {
    problem(std::to_string(orderId) + " was reduced from " + std::to_string(held->quantity) +
            " to " + std::to_string(quantity));
    return;
  }
  held->quantity = quantity;
}

void ConsumerBook::removed(const std::uint64_t orderId, const std::int64_t quantity) {
  Entry* held = resting(orderId);
  if (held == nullptr) {
    if (everRested_.contains(orderId)) {
      // It rested, it left, and here it goes again. Twice removed is once too many.
      problem(std::to_string(orderId) + " removed after it had already left the book");
    } else if (!accepted_.contains(orderId)) {
      problem(std::to_string(orderId) + " removed without having been accepted");
    }
    // Otherwise it was accepted and never rested, which is a stop or an unfilled remainder
    // leaving.
    return;
  }
  if (quantity != held->quantity) {
    problem(std::to_string(orderId) + " removed " + std::to_string(quantity) + " with " +
            std::to_string(held->quantity) + " showing");
  }
  erase(orderId);
}

ConsumerBook::Entry* ConsumerBook::resting(const std::uint64_t orderId) {
  const auto found = std::find_if(entries_.begin(), entries_.end(), [orderId](const Entry& entry) {
    return entry.orderId == orderId;
  });
  return found == entries_.end() ? nullptr : &*found;
}

void ConsumerBook::erase(const std::uint64_t orderId) {
  std::erase_if(entries_, [orderId](const Entry& entry) { return entry.orderId == orderId; });
}

void ConsumerBook::problem(std::string description) { problems_.push_back(std::move(description)); }

}  // namespace io::github::giovanicaprison::matching::conformance

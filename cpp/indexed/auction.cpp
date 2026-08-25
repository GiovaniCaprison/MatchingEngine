#include "indexed/auction.hpp"

#include <algorithm>
#include <cstdlib>
#include <optional>
#include <vector>

namespace io::github::giovanicaprison::matching::indexed {

namespace {

using Side = protocol::Side::Value;

// One price, with everything the tie-break needs to know about it: how much would trade there, how
// much would be left unfilled on whichever side is longer, and the side the surplus sits on, or
// nothing when the two balance.
struct Candidate {
  std::int64_t price = 0;
  std::int64_t tradeable = 0;
  std::int64_t surplus = 0;
  std::optional<Side> pressure;
};

// How much one side would trade at a price: everyone who named that price or better.
std::int64_t quantityWilling(const Book& book, const Side side, const std::int64_t price) {
  std::int64_t total = 0;
  for (const OrderPtr& order : book.orders()) {
    if (order->side() == side && order->willingAt(price)) {
      total += order->remaining();
    }
  }
  return total;
}

Candidate priced(const Book& book, const std::int64_t price) {
  const std::int64_t demand = quantityWilling(book, protocol::Side::BUY, price);
  const std::int64_t supply = quantityWilling(book, protocol::Side::SELL, price);
  std::optional<Side> pressure;
  if (demand != supply) {
    pressure = demand > supply ? protocol::Side::BUY : protocol::Side::SELL;
  }
  return Candidate{price, std::min(demand, supply), std::abs(demand - supply), pressure};
}

// Which of two candidates a venue would choose (FR-7.5). Volume first, then the smaller surplus,
// and then the side the surplus is on: unfilled demand is buying pressure and settles high,
// unfilled supply settles low. Below that the reference price decides, which covers candidates
// that balance exactly and candidates whose equal surpluses sit on opposite sides, and the last
// rule takes the higher price, so nothing is left to the order the candidates were walked in.
bool better(const Candidate& one, const Candidate& than, const std::int64_t reference) {
  if (one.tradeable != than.tradeable) {
    return one.tradeable > than.tradeable;
  }
  if (one.surplus != than.surplus) {
    return one.surplus < than.surplus;
  }
  if (one.pressure == than.pressure && one.pressure == protocol::Side::BUY) {
    return one.price > than.price;
  }
  if (one.pressure == than.pressure && one.pressure == protocol::Side::SELL) {
    return one.price < than.price;
  }
  const std::int64_t distance = std::abs(one.price - reference);
  const std::int64_t otherDistance = std::abs(than.price - reference);
  if (distance != otherDistance) {
    return distance < otherDistance;
  }
  return one.price > than.price;
}

// Every price anyone has named, since the uncrossing price is always one of them.
std::vector<std::int64_t> candidates(const Book& book) {
  std::vector<std::int64_t> prices;
  for (const OrderPtr& order : book.orders()) {
    if (std::find(prices.begin(), prices.end(), order->price()) == prices.end()) {
      prices.push_back(order->price());
    }
  }
  std::sort(prices.begin(), prices.end());
  return prices;
}

}  // namespace

Auction::Uncrossing Auction::uncrossing(const Book& book, const std::int64_t reference) {
  std::optional<Candidate> best;
  for (const std::int64_t price : candidates(book)) {
    const Candidate candidate = priced(book, price);
    if (candidate.tradeable == 0) {
      continue;
    }
    if (!best.has_value() || better(candidate, *best, reference)) {
      best = candidate;
    }
  }
  if (!best.has_value()) {
    return Uncrossing{};
  }
  return Uncrossing{best->price, best->tradeable};
}

}  // namespace io::github::giovanicaprison::matching::indexed

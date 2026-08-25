#include "pooled/auction.hpp"

#include <cstdlib>

namespace io::github::giovanicaprison::matching::pooled {

namespace {
using Side = protocol::Side;
}

void Auction::uncross(const Book& book, const std::int64_t reference) {
  have_ = false;
  consider(book, Side::BUY, reference);
  consider(book, Side::SELL, reference);
  price_ = have_ ? bestPrice_ : 0;
  quantity_ = have_ ? bestTradeable_ : 0;
}

// Every price this side has named, since the uncrossing price is always one someone named. A price
// both sides named is considered twice, which is harmless: the tie-break is a total preference
// over distinct prices, so a candidate never displaces itself.
void Auction::consider(const Book& book, const Side::Value side, const std::int64_t reference) {
  book.walk(side, candidates_);
  for (Book::Level* level = candidates_.next(); level != nullptr; level = candidates_.next()) {
    const std::int64_t candidate = level->price;
    const std::int64_t demand = quantityWilling(book, Side::BUY, candidate);
    const std::int64_t supply = quantityWilling(book, Side::SELL, candidate);
    const std::int64_t tradeable = std::min(demand, supply);
    if (tradeable == 0) {
      continue;
    }
    const std::int64_t surplus = std::abs(demand - supply);
    const Side::Value pressure = demand == supply  ? Side::NULL_VALUE
                                 : demand > supply ? Side::BUY
                                                   : Side::SELL;
    if (!have_ || better(candidate, tradeable, surplus, pressure, reference)) {
      have_ = true;
      bestPrice_ = candidate;
      bestTradeable_ = tradeable;
      bestSurplus_ = surplus;
      bestPressure_ = pressure;
    }
  }
}

// Which of two candidates a venue would choose (FR-7.5). Volume first, then the smaller surplus,
// and then the side the surplus is on: unfilled demand is buying pressure and settles high,
// unfilled supply settles low. Below that the reference price decides, and the last rule takes the
// higher price, so that nothing is left to the order the candidates happened to be walked in.
bool Auction::better(const std::int64_t candidate, const std::int64_t tradeable,
                     const std::int64_t surplus, const Side::Value pressure,
                     const std::int64_t reference) const {
  if (tradeable != bestTradeable_) {
    return tradeable > bestTradeable_;
  }
  if (surplus != bestSurplus_) {
    return surplus < bestSurplus_;
  }
  if (pressure == bestPressure_ && pressure == Side::BUY) {
    return candidate > bestPrice_;
  }
  if (pressure == bestPressure_ && pressure == Side::SELL) {
    return candidate < bestPrice_;
  }
  const std::int64_t distance = std::abs(candidate - reference);
  const std::int64_t bestDistance = std::abs(bestPrice_ - reference);
  if (distance != bestDistance) {
    return distance < bestDistance;
  }
  return candidate > bestPrice_;
}

// How much one side would trade at a price: everyone who named that price or better. The side's
// levels are walked best first, so the willing levels are a prefix and the walk stops at the first
// level that is not.
std::int64_t Auction::quantityWilling(const Book& book, const Side::Value side,
                                      const std::int64_t candidate) {
  std::int64_t total = 0;
  book.walk(side, willing_);
  for (Book::Level* level = willing_.next(); level != nullptr; level = willing_.next()) {
    if (side == Side::BUY ? level->price < candidate : level->price > candidate) {
      return total;
    }
    for (const Order* order = level->head; order != nullptr; order = order->next_) {
      total += order->remaining();
    }
  }
  return total;
}

}  // namespace io::github::giovanicaprison::matching::pooled

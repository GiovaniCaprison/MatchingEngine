#include "flyweight/auction.hpp"

#include <algorithm>
#include <cstdlib>

namespace io::github::giovanicaprison::matching::flyweight {

void Auction::uncross(const Book& book, const std::int64_t reference) {
  have_ = false;
  consider(book, 0, reference);
  consider(book, 1, reference);
  price_ = have_ ? bestPrice_ : 0;
  quantity_ = have_ ? bestTradeable_ : 0;
}

// Every price this side has named, since the uncrossing price is always one someone named. A price
// both sides named is considered twice, which is harmless: the tie-break is a total preference
// over distinct prices, so a candidate never displaces itself.
void Auction::consider(const Book& book, const std::int32_t side, const std::int64_t reference) {
  for (std::int32_t rank = book.firstRank(side); rank != Ladder::EMPTY;
       rank = book.rankAfter(side, rank)) {
    const std::int64_t candidate = book.priceOfRank(side, rank);
    const std::int64_t demand = quantityWilling(book, 0, candidate);
    const std::int64_t supply = quantityWilling(book, 1, candidate);
    const std::int64_t tradeable = std::min(demand, supply);
    if (tradeable == 0) {
      continue;
    }
    const std::int64_t surplus = std::abs(demand - supply);
    const std::int32_t pressure = demand == supply ? NONE : demand > supply ? 0 : 1;
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
// higher price, so nothing is left to the order the candidates were walked in.
bool Auction::better(const std::int64_t candidate, const std::int64_t tradeable,
                     const std::int64_t surplus, const std::int32_t pressure,
                     const std::int64_t reference) const {
  if (tradeable != bestTradeable_) {
    return tradeable > bestTradeable_;
  }
  if (surplus != bestSurplus_) {
    return surplus < bestSurplus_;
  }
  if (pressure == bestPressure_ && pressure == 0) {
    return candidate > bestPrice_;
  }
  if (pressure == bestPressure_ && pressure == 1) {
    return candidate < bestPrice_;
  }
  const std::int64_t distance = std::abs(candidate - reference);
  const std::int64_t bestDistance = std::abs(bestPrice_ - reference);
  if (distance != bestDistance) {
    return distance < bestDistance;
  }
  return candidate > bestPrice_;
}

// How much one side would trade at a price: everyone who named that price or better, which in
// rank space is a prefix of the occupied ranks, summed from the cached totals (NFR-3.1).
std::int64_t Auction::quantityWilling(const Book& book, const std::int32_t side,
                                      const std::int64_t candidate) {
  const std::int32_t limit = book.willingLimitRank(side, candidate);
  std::int64_t total = 0;
  for (std::int32_t rank = book.firstRank(side); rank <= limit; rank = book.rankAfter(side, rank)) {
    total += book.remainingAtRank(side, rank);
  }
  return total;
}

}  // namespace io::github::giovanicaprison::matching::flyweight

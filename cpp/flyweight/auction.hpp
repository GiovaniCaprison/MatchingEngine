// What an auction would do, and what it does. Every occupied price is a candidate and each one is
// priced against what both sides would trade there, so the asking stays quadratic in the book's
// prices, which is the honest cost of the question at every rung. What this rung removes is the
// touching: a side's willingness at a price is a prefix sum over the ladder's cached remaining
// totals, read off the occupied ranks the bitmap names, so no order is visited however often the
// indicative is recomputed (FR-7.7). Hidden quantity is counted, because the totals carry
// remaining and an iceberg's concealed part is real liquidity a venue lets trade in an uncrossing.

#pragma once

#include <cstdint>

#include "flyweight/book.hpp"

namespace io::github::giovanicaprison::matching::flyweight {

class Auction {
 public:
  // Where it would trade, or zero when nothing crosses.
  std::int64_t price() const { return price_; }

  // How much would trade there.
  std::int64_t quantity() const { return quantity_; }

  bool crosses() const { return quantity_ > 0; }

  void uncross(const Book& book, std::int64_t reference);

 private:
  static constexpr std::int32_t NONE = -1;

  void consider(const Book& book, std::int32_t side, std::int64_t reference);
  bool better(std::int64_t candidate, std::int64_t tradeable, std::int64_t surplus,
              std::int32_t pressure, std::int64_t reference) const;
  static std::int64_t quantityWilling(const Book& book, std::int32_t side, std::int64_t candidate);

  std::int64_t price_ = 0;
  std::int64_t quantity_ = 0;

  bool have_ = false;
  std::int64_t bestPrice_ = 0;
  std::int64_t bestTradeable_ = 0;
  std::int64_t bestSurplus_ = 0;
  std::int32_t bestPressure_ = NONE;
};

}  // namespace io::github::giovanicaprison::matching::flyweight

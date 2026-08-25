// What an auction would do, and what it does. Every distinct price in the book is a candidate and
// each one is priced by walking the levels it could trade against, so the asking stays quadratic
// in the book's prices, which is this rung's honest cost of the question. What the rung removes is
// the memory the rung below spent asking it: the candidates are read off the level trees where
// they already sit sorted, the tally is a handful of fields, and nothing is allocated however
// often the indicative is recomputed (NFR-4.3). Hidden quantity is counted, because an iceberg's
// concealed part is real liquidity and a price that ignored it would leave the book crossed.

#pragma once

#include <cstdint>

#include "pooled/book.hpp"

namespace io::github::giovanicaprison::matching::pooled {

class Auction {
 public:
  // Where it would trade, or zero when nothing crosses.
  std::int64_t price() const { return price_; }

  // How much would trade there.
  std::int64_t quantity() const { return quantity_; }

  bool crosses() const { return quantity_ > 0; }

  void uncross(const Book& book, std::int64_t reference);

 private:
  void consider(const Book& book, protocol::Side::Value side, std::int64_t reference);
  bool better(std::int64_t candidate, std::int64_t tradeable, std::int64_t surplus,
              protocol::Side::Value pressure, std::int64_t reference) const;
  std::int64_t quantityWilling(const Book& book, protocol::Side::Value side,
                               std::int64_t candidate);

  Book::Walk candidates_;
  Book::Walk willing_;

  std::int64_t price_ = 0;
  std::int64_t quantity_ = 0;

  bool have_ = false;
  std::int64_t bestPrice_ = 0;
  std::int64_t bestTradeable_ = 0;
  std::int64_t bestSurplus_ = 0;
  protocol::Side::Value bestPressure_ = protocol::Side::NULL_VALUE;
};

}  // namespace io::github::giovanicaprison::matching::pooled

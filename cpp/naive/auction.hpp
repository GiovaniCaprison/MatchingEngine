// What an auction would do, and what it does. Every distinct price in the book is a candidate and
// each one is priced by walking the whole book, so this is quadratic in the number of orders; on
// this rung that is the honest cost of asking. Hidden quantity is counted, because an iceberg's
// concealed part is real liquidity and a price that ignored it would leave the book crossed. What
// it does not buy is allocation priority, which is decided elsewhere.

#pragma once

#include <cstdint>

#include "naive/book.hpp"

namespace io::github::giovanicaprison::matching::naive {

class Auction {
 public:
  // The outcome of an uncrossing: where it would trade, or zero when nothing crosses, and how
  // much would trade there.
  struct Uncrossing {
    std::int64_t price = 0;
    std::int64_t quantity = 0;

    bool crosses() const { return quantity > 0; }
  };

  static Uncrossing uncrossing(const Book& book, std::int64_t reference);

  Auction() = delete;
};

}  // namespace io::github::giovanicaprison::matching::naive

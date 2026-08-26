// One side's price levels as a flat array indexed by rank, with an occupancy bitmap above it, at
// the layout the Java rung carries. A rank is the side's own view of a tick: the ask ladder ranks
// ticks as they are and the bid ladder ranks them reversed, so rank zero is the best price on
// either side and every walk, every crossing test and every best-price search is the same
// arithmetic. A level is two words: the queue's head and tail packed into one, and its cached
// displayed and remaining totals interleaved in another array so one line carries both (NFR-3.1).
// Best price discovery never scans: a three level bitmap says which ranks are occupied, and the
// lowest occupied rank falls out of three trailing-zero counts. The bits change exactly when a
// queue becomes empty or stops being empty, so the summary can never quietly disagree with the
// ladder (NFR-3.2).

#pragma once

#include <bit>
#include <cstdint>
#include <limits>
#include <vector>

#include "flyweight/slab.hpp"

namespace io::github::giovanicaprison::matching::flyweight {

class Ladder {
 public:
  // No occupied rank, above every reachable limit rank by construction.
  static constexpr std::int32_t EMPTY = std::numeric_limits<std::int32_t>::max();

  Ladder(Slab& slab, const std::int32_t ranks) : slab_(slab), ranks_(ranks) {
    queues_.assign(static_cast<std::size_t>(ranks), 0);
    totals_.assign(static_cast<std::size_t>(ranks) << 1, 0);
    bits0_.assign(words(static_cast<std::size_t>(ranks)), 0);
    bits1_.assign(words(bits0_.size()), 0);
    bits2_.assign(words(bits1_.size()), 0);
  }

  // The best occupied rank, or EMPTY, cached so the common question costs one read.
  std::int32_t best() const { return best_; }

  std::int32_t headAt(const std::int32_t rank) const {
    return static_cast<std::int32_t>(queues_[static_cast<std::size_t>(rank)] & 0xFFFFFFFF);
  }

  std::int64_t displayedAt(const std::int32_t rank) const {
    return totals_[static_cast<std::size_t>(rank) << 1];
  }

  std::int64_t remainingAt(const std::int32_t rank) const {
    return totals_[(static_cast<std::size_t>(rank) << 1) + 1];
  }

  // The order's quantities changed in place, so the level's totals follow (NFR-3.1).
  void adjust(const std::int32_t rank, const std::int64_t displayedDelta,
              const std::int64_t remainingDelta) {
    totals_[static_cast<std::size_t>(rank) << 1] += displayedDelta;
    totals_[(static_cast<std::size_t>(rank) << 1) + 1] += remainingDelta;
  }

  // Joins the back of the queue at the rank, opening the level if nobody was there.
  void append(const std::int32_t rank, const std::int32_t slot) {
    const std::uint64_t queue = queues_[static_cast<std::size_t>(rank)];
    const std::int32_t tail = static_cast<std::int32_t>(queue >> 32);
    slab_.link(slot, tail, 0);
    if (tail == 0) {
      queues_[static_cast<std::size_t>(rank)] = pack(slot, slot);
      set(rank);
      best_ = std::min(best_, rank);
    } else {
      slab_.linkNext(tail, slot);
      queues_[static_cast<std::size_t>(rank)] =
          pack(static_cast<std::int32_t>(queue & 0xFFFFFFFF), slot);
    }
    adjust(rank, slab_.displayed(slot), slab_.remaining(slot));
  }

  // Detaches the slot from its queue completely (P-13), closing the level when it empties: an
  // empty level does not survive, so the bitmap and the best cache never point at a price nobody
  // is at (NFR-3.2).
  void unlink(const std::int32_t rank, const std::int32_t slot) {
    adjust(rank, -slab_.displayed(slot), -slab_.remaining(slot));
    const std::int32_t previous = slab_.previous(slot);
    const std::int32_t next = slab_.next(slot);
    const std::uint64_t queue = queues_[static_cast<std::size_t>(rank)];
    std::int32_t head = static_cast<std::int32_t>(queue & 0xFFFFFFFF);
    std::int32_t tail = static_cast<std::int32_t>(queue >> 32);
    if (previous == 0) {
      head = next;
    } else {
      slab_.linkNext(previous, next);
    }
    if (next == 0) {
      tail = previous;
    } else {
      slab_.linkPrevious(next, previous);
    }
    slab_.link(slot, 0, 0);
    queues_[static_cast<std::size_t>(rank)] = pack(head, tail);
    if (head == 0) {
      clear(rank);
      if (rank == best_) {
        best_ = occupiedFrom(rank);
      }
    }
  }

  // A replenished tranche joins the back of the queue at its price (FR-5.4).
  void requeue(const std::int32_t rank, const std::int32_t slot, const std::int64_t displayedDelta,
               const std::int64_t remainingDelta) {
    const std::int32_t previous = slab_.previous(slot);
    const std::int32_t next = slab_.next(slot);
    const std::uint64_t queue = queues_[static_cast<std::size_t>(rank)];
    const std::int32_t head = previous == 0 ? next : static_cast<std::int32_t>(queue & 0xFFFFFFFF);
    const std::int32_t tail = static_cast<std::int32_t>(queue >> 32);
    if (slot != tail) {
      if (previous == 0) {
        slab_.linkPrevious(next, 0);
      } else {
        slab_.linkNext(previous, next);
        slab_.linkPrevious(next, previous);
      }
      slab_.link(slot, tail, 0);
      slab_.linkNext(tail, slot);
      queues_[static_cast<std::size_t>(rank)] = pack(head, slot);
    }
    adjust(rank, displayedDelta, remainingDelta);
  }

  // The lowest occupied rank at or above the argument, or EMPTY: one masked word per level going
  // up, one trailing-zero count per level coming down, never a scan of the ladder.
  std::int32_t occupiedFrom(const std::int32_t rank) const {
    std::size_t word0 = static_cast<std::size_t>(rank) >> 6;
    if (word0 >= bits0_.size()) {
      return EMPTY;
    }
    const std::uint64_t low = bits0_[word0] & (~std::uint64_t{0} << (rank & 63));
    if (low != 0) {
      return static_cast<std::int32_t>((word0 << 6) +
                                       static_cast<std::size_t>(std::countr_zero(low)));
    }
    std::size_t word1 = word0 >> 6;
    std::uint64_t mid = above(bits1_[word1], word0 & 63);
    if (mid == 0) {
      std::size_t word2 = word1 >> 6;
      std::uint64_t high = above(bits2_[word2], word1 & 63);
      while (high == 0) {
        word2++;
        if (word2 == bits2_.size()) {
          return EMPTY;
        }
        high = bits2_[word2];
      }
      word1 = (word2 << 6) + static_cast<std::size_t>(std::countr_zero(high));
      mid = bits1_[word1];
    }
    word0 = (word1 << 6) + static_cast<std::size_t>(std::countr_zero(mid));
    return static_cast<std::int32_t>((word0 << 6) +
                                     static_cast<std::size_t>(std::countr_zero(bits0_[word0])));
  }

  std::int32_t rankCount() const { return ranks_; }

 private:
  static std::size_t words(const std::size_t bits) { return (bits + 63) >> 6; }

  static std::uint64_t pack(const std::int32_t head, const std::int32_t tail) {
    return (static_cast<std::uint64_t>(tail) << 32) | static_cast<std::uint32_t>(head);
  }

  // The bits of the word strictly above the given bit, safe at the top where a shift wraps.
  static std::uint64_t above(const std::uint64_t word, const std::size_t bit) {
    return bit == 63 ? 0 : word & (~std::uint64_t{0} << (bit + 1));
  }

  void set(const std::int32_t rank) {
    const std::size_t word0 = static_cast<std::size_t>(rank) >> 6;
    const std::size_t word1 = word0 >> 6;
    bits0_[word0] |= std::uint64_t{1} << (rank & 63);
    bits1_[word1] |= std::uint64_t{1} << (word0 & 63);
    bits2_[word1 >> 6] |= std::uint64_t{1} << (word1 & 63);
  }

  void clear(const std::int32_t rank) {
    const std::size_t word0 = static_cast<std::size_t>(rank) >> 6;
    if ((bits0_[word0] &= ~(std::uint64_t{1} << (rank & 63))) != 0) {
      return;
    }
    const std::size_t word1 = word0 >> 6;
    if ((bits1_[word1] &= ~(std::uint64_t{1} << (word0 & 63))) != 0) {
      return;
    }
    bits2_[word1 >> 6] &= ~(std::uint64_t{1} << (word1 & 63));
  }

  Slab& slab_;
  std::int32_t ranks_;

  // Head in the low 32 bits, tail in the high 32, zero meaning nobody is at the price.
  std::vector<std::uint64_t> queues_;

  // Displayed total at rank << 1, remaining total beside it (NFR-3.1).
  std::vector<std::int64_t> totals_;

  std::vector<std::uint64_t> bits0_;
  std::vector<std::uint64_t> bits1_;
  std::vector<std::uint64_t> bits2_;

  std::int32_t best_ = EMPTY;
};

}  // namespace io::github::giovanicaprison::matching::flyweight

// The flyweight rung's book with nothing on it the lean remit does not need, at a layout matched
// to the Java arm: the same flat ladder per side in the same folded rank space, the same three
// level occupancy bitmap under the same cached best, and the same interleaved open-addressing name
// index, so the comparison between the two arms isolates the feature set at this layout and not a
// structural difference (P-16). What is missing is the feature machinery: one cached total per
// level instead of two, since nothing here hides quantity, and no fillable pre-check, no pro-rata
// snapshot, no re-queueing, because nothing here replenishes. And like the rung it shadows, the
// steady state allocates nothing (NFR-4.3).

#pragma once

#include <algorithm>
#include <bit>
#include <cstdint>
#include <limits>
#include <vector>

#include "lean-flyweight/slab.hpp"

namespace io::github::giovanicaprison::matching::lean::flyweight {

class Book {
 public:
  // No occupied rank, above every reachable limit rank by construction.
  static constexpr std::int32_t EMPTY = std::numeric_limits<std::int32_t>::max();

  Book(Slab& slab, const std::int64_t tickSize, const std::int64_t baseTick,
       const std::int32_t rankCount)
      : slab_(slab), tickSize_(tickSize), baseTick_(baseTick), maxRank_(rankCount - 1) {
    bidQueues_.assign(static_cast<std::size_t>(rankCount), 0);
    askQueues_.assign(static_cast<std::size_t>(rankCount), 0);
    bidTotals_.assign(static_cast<std::size_t>(rankCount), 0);
    askTotals_.assign(static_cast<std::size_t>(rankCount), 0);
    bidBits0_.assign(words(static_cast<std::size_t>(rankCount)), 0);
    bidBits1_.assign(words(bidBits0_.size()), 0);
    bidBits2_.assign(words(bidBits1_.size()), 0);
    askBits0_.assign(words(static_cast<std::size_t>(rankCount)), 0);
    askBits1_.assign(words(askBits0_.size()), 0);
    askBits2_.assign(words(askBits1_.size()), 0);
    allocateNames(1 << 16);
  }

  std::int64_t priceOfTick(const std::int32_t tick) const { return (tick + baseTick_) * tickSize_; }

  // The tick of a price validation already admitted (P-5); never checked here (P-14).
  std::int32_t tickOfPrice(const std::int64_t price) const {
    return static_cast<std::int32_t>(price / tickSize_ - baseTick_);
  }

  // A side's view of a tick: bids rank best-first by reversing, asks are already ascending.
  std::int32_t rankOf(const std::int32_t side, const std::int32_t tick) const {
    return side == 0 ? maxRank_ - tick : tick;
  }

  // Every rank a market order can reach, which is all of them and never EMPTY.
  std::int32_t marketLimit() const { return maxRank_; }

  // The order a taker reaches next: the front of the best crossing level (FR-3.1, FR-3.3).
  std::int32_t nextToTake(const std::int32_t takerSide, const std::int32_t limitRank) const {
    if (takerSide == 0) {
      return bestAsk_ > limitRank
                 ? 0
                 : static_cast<std::int32_t>(askQueues_[static_cast<std::size_t>(bestAsk_)] &
                                             0xFFFFFFFF);
    }
    return bestBid_ > limitRank ? 0
                                : static_cast<std::int32_t>(
                                      bidQueues_[static_cast<std::size_t>(bestBid_)] & 0xFFFFFFFF);
  }

  void add(const std::int32_t side, const std::int32_t slot) {
    const std::int32_t rank = rankOf(side, slab_.tick(slot));
    std::vector<std::uint64_t>& queues = side == 0 ? bidQueues_ : askQueues_;
    const std::uint64_t queue = queues[static_cast<std::size_t>(rank)];
    const std::int32_t tail = static_cast<std::int32_t>(queue >> 32);
    slab_.link(slot, tail, 0);
    if (tail == 0) {
      queues[static_cast<std::size_t>(rank)] = pack(slot, slot);
      set(side, rank);
      if (side == 0) {
        bestBid_ = std::min(bestBid_, rank);
      } else {
        bestAsk_ = std::min(bestAsk_, rank);
      }
    } else {
      slab_.linkNext(tail, slot);
      queues[static_cast<std::size_t>(rank)] =
          pack(static_cast<std::int32_t>(queue & 0xFFFFFFFF), slot);
    }
    (side == 0 ? bidTotals_ : askTotals_)[static_cast<std::size_t>(rank)] += slab_.remaining(slot);
    namePut(slot);
  }

  void remove(const std::int32_t side, const std::int32_t slot) {
    const std::int32_t rank = rankOf(side, slab_.tick(slot));
    std::vector<std::uint64_t>& queues = side == 0 ? bidQueues_ : askQueues_;
    (side == 0 ? bidTotals_ : askTotals_)[static_cast<std::size_t>(rank)] -= slab_.remaining(slot);
    const std::int32_t previous = slab_.previous(slot);
    const std::int32_t next = slab_.next(slot);
    const std::uint64_t queue = queues[static_cast<std::size_t>(rank)];
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
    queues[static_cast<std::size_t>(rank)] = pack(head, tail);
    if (head == 0) {
      // (NFR-3.2) An empty level does not survive: the bit clears and the best moves on, so the
      // summary never points at a price nobody is at.
      clear(side, rank);
      if (side == 0) {
        if (rank == bestBid_) {
          bestBid_ = occupiedFrom(0, rank);
        }
      } else if (rank == bestAsk_) {
        bestAsk_ = occupiedFrom(1, rank);
      }
    }
    nameRemove(slab_.participantId(slot), slab_.clientOrderId(slot));
  }

  // The order's remaining quantity changed in place, so the level's total follows (NFR-3.1).
  void quantityChanged(const std::int32_t side, const std::int32_t slot, const std::int64_t delta) {
    (side == 0 ? bidTotals_
               : askTotals_)[static_cast<std::size_t>(rankOf(side, slab_.tick(slot)))] += delta;
  }

  // The slot resting under the name, or zero (FR-4.1).
  std::int32_t named(const std::uint32_t participantId, const std::uint64_t clientOrderId) const {
    std::size_t at = slotOf(participantId, clientOrderId);
    while (slotAt(at) != 0) {
      if (names_[at << 1] == clientOrderId &&
          static_cast<std::uint32_t>(names_[(at << 1) + 1]) == participantId) {
        return slotAt(at);
      }
      at = (at + 1) & nameMask_;
    }
    return 0;
  }

  // Every resting order for one participant appended to the caller's space (FR-4.7).
  void of(const std::uint32_t participantId, std::vector<std::int32_t>& into) const {
    for (std::size_t at = 0; at <= nameMask_; at++) {
      const std::uint64_t entry = names_[(at << 1) + 1];
      if (static_cast<std::int32_t>(entry >> 32) != 0 &&
          static_cast<std::uint32_t>(entry) == participantId) {
        into.push_back(static_cast<std::int32_t>(entry >> 32));
      }
    }
  }

 private:
  static std::size_t words(const std::size_t bits) { return (bits + 63) >> 6; }

  static std::uint64_t pack(const std::int32_t head, const std::int32_t tail) {
    return (static_cast<std::uint64_t>(tail) << 32) | static_cast<std::uint32_t>(head);
  }

  // The bits of the word strictly above the given bit, safe at the top where a shift wraps.
  static std::uint64_t above(const std::uint64_t word, const std::size_t bit) {
    return bit == 63 ? 0 : word & (~std::uint64_t{0} << (bit + 1));
  }

  void set(const std::int32_t side, const std::int32_t rank) {
    const std::size_t word0 = static_cast<std::size_t>(rank) >> 6;
    const std::size_t word1 = word0 >> 6;
    if (side == 0) {
      bidBits0_[word0] |= std::uint64_t{1} << (rank & 63);
      bidBits1_[word1] |= std::uint64_t{1} << (word0 & 63);
      bidBits2_[word1 >> 6] |= std::uint64_t{1} << (word1 & 63);
    } else {
      askBits0_[word0] |= std::uint64_t{1} << (rank & 63);
      askBits1_[word1] |= std::uint64_t{1} << (word0 & 63);
      askBits2_[word1 >> 6] |= std::uint64_t{1} << (word1 & 63);
    }
  }

  void clear(const std::int32_t side, const std::int32_t rank) {
    std::vector<std::uint64_t>& bits0 = side == 0 ? bidBits0_ : askBits0_;
    const std::size_t word0 = static_cast<std::size_t>(rank) >> 6;
    if ((bits0[word0] &= ~(std::uint64_t{1} << (rank & 63))) != 0) {
      return;
    }
    std::vector<std::uint64_t>& bits1 = side == 0 ? bidBits1_ : askBits1_;
    const std::size_t word1 = word0 >> 6;
    if ((bits1[word1] &= ~(std::uint64_t{1} << (word0 & 63))) != 0) {
      return;
    }
    (side == 0 ? bidBits2_ : askBits2_)[word1 >> 6] &= ~(std::uint64_t{1} << (word1 & 63));
  }

  // The lowest occupied rank at or above the argument, three trailing-zero counts away.
  std::int32_t occupiedFrom(const std::int32_t side, const std::int32_t rank) const {
    const std::vector<std::uint64_t>& bits0 = side == 0 ? bidBits0_ : askBits0_;
    const std::vector<std::uint64_t>& bits1 = side == 0 ? bidBits1_ : askBits1_;
    const std::vector<std::uint64_t>& bits2 = side == 0 ? bidBits2_ : askBits2_;
    std::size_t word0 = static_cast<std::size_t>(rank) >> 6;
    if (word0 >= bits0.size()) {
      return EMPTY;
    }
    const std::uint64_t low = bits0[word0] & (~std::uint64_t{0} << (rank & 63));
    if (low != 0) {
      return static_cast<std::int32_t>((word0 << 6) +
                                       static_cast<std::size_t>(std::countr_zero(low)));
    }
    std::size_t word1 = word0 >> 6;
    std::uint64_t mid = above(bits1[word1], word0 & 63);
    if (mid == 0) {
      std::size_t word2 = word1 >> 6;
      std::uint64_t high = above(bits2[word2], word1 & 63);
      while (high == 0) {
        word2++;
        if (word2 == bits2.size()) {
          return EMPTY;
        }
        high = bits2[word2];
      }
      word1 = (word2 << 6) + static_cast<std::size_t>(std::countr_zero(high));
      mid = bits1[word1];
    }
    word0 = (word1 << 6) + static_cast<std::size_t>(std::countr_zero(mid));
    return static_cast<std::int32_t>((word0 << 6) +
                                     static_cast<std::size_t>(std::countr_zero(bits0[word0])));
  }

  // The name index ------------------------------------------------------------------------------

  std::int32_t slotAt(const std::size_t at) const {
    return static_cast<std::int32_t>(names_[(at << 1) + 1] >> 32);
  }

  void allocateNames(const std::size_t capacity) {
    names_.assign(capacity << 1, 0);
    nameMask_ = capacity - 1;
  }

  std::size_t slotOf(const std::uint32_t participantId, const std::uint64_t clientOrderId) const {
    std::uint64_t hash =
        clientOrderId * 0x9E3779B97F4A7C15ULL ^ participantId * 0xC2B2AE3D27D4EB4FULL;
    hash ^= hash >> 32;
    return static_cast<std::size_t>(hash) & nameMask_;
  }

  void namePut(const std::int32_t slot) {
    if ((nameCount_ + 1) * 2 > nameMask_ + 1) {
      grow();
    }
    const std::uint32_t participantId = slab_.participantId(slot);
    const std::uint64_t clientOrderId = slab_.clientOrderId(slot);
    std::size_t at = slotOf(participantId, clientOrderId);
    while (slotAt(at) != 0) {
      at = (at + 1) & nameMask_;
    }
    names_[at << 1] = clientOrderId;
    names_[(at << 1) + 1] = participantId | (static_cast<std::uint64_t>(slot) << 32);
    nameCount_++;
  }

  void nameRemove(const std::uint32_t participantId, const std::uint64_t clientOrderId) {
    std::size_t at = slotOf(participantId, clientOrderId);
    while (names_[at << 1] != clientOrderId ||
           static_cast<std::uint32_t>(names_[(at << 1) + 1]) != participantId) {
      at = (at + 1) & nameMask_;
    }
    nameCount_--;
    // Backward shift rather than a tombstone: the table's occupancy is its contents, so the load
    // never quietly climbs and the steady state never rehashes (NFR-4.3).
    std::size_t hole = at;
    std::size_t probe = (hole + 1) & nameMask_;
    while (slotAt(probe) != 0) {
      const std::size_t home =
          slotOf(static_cast<std::uint32_t>(names_[(probe << 1) + 1]), names_[probe << 1]);
      const bool reachable = ((probe - home) & nameMask_) >= ((probe - hole) & nameMask_);
      if (reachable) {
        names_[hole << 1] = names_[probe << 1];
        names_[(hole << 1) + 1] = names_[(probe << 1) + 1];
        hole = probe;
      }
      probe = (probe + 1) & nameMask_;
    }
    names_[(hole << 1) + 1] = 0;
  }

  void grow() {
    const std::vector<std::uint64_t> old = std::move(names_);
    allocateNames((nameMask_ + 1) * 2);
    nameCount_ = 0;
    for (std::size_t at = 0; at < old.size(); at += 2) {
      const std::int32_t slot = static_cast<std::int32_t>(old[at + 1] >> 32);
      if (slot != 0) {
        nameCount_++;
        std::size_t to = slotOf(static_cast<std::uint32_t>(old[at + 1]), old[at]);
        while (slotAt(to) != 0) {
          to = (to + 1) & nameMask_;
        }
        names_[to << 1] = old[at];
        names_[(to << 1) + 1] = old[at + 1];
      }
    }
  }

  Slab& slab_;
  std::int64_t tickSize_;
  std::int64_t baseTick_;
  std::int32_t maxRank_;

  // Head in the low 32 bits, tail in the high 32, zero meaning nobody is at the price.
  std::vector<std::uint64_t> bidQueues_;
  std::vector<std::uint64_t> askQueues_;

  // The cached remaining total per level, the number the queue must always sum to (NFR-3.1).
  std::vector<std::int64_t> bidTotals_;
  std::vector<std::int64_t> askTotals_;

  std::vector<std::uint64_t> bidBits0_;
  std::vector<std::uint64_t> bidBits1_;
  std::vector<std::uint64_t> bidBits2_;
  std::vector<std::uint64_t> askBits0_;
  std::vector<std::uint64_t> askBits1_;
  std::vector<std::uint64_t> askBits2_;

  std::int32_t bestBid_ = EMPTY;
  std::int32_t bestAsk_ = EMPTY;

  // Client order id at entry << 1; participant in the low half beside it, slot above.
  std::vector<std::uint64_t> names_;

  std::size_t nameMask_ = 0;
  std::size_t nameCount_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::lean::flyweight

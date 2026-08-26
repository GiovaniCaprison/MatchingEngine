// Every order this twin holds, as slots in one preallocated array of 64 bit words, with nothing on
// a slot a limit or market order does not need, at a layout matched to the Java arm. This is the
// object the comparison is about (P-16): the full rung's stride carries a trigger price, a display
// size, a minimum quantity, a self match id and a displayed quantity, and every one of them
// occupies the layout whether or not the flow uses it. Here they do not exist, so the stride is
// eight words and a whole order is one cache line. Everything else is the full rung's slab: slot
// zero is the null link, the free list threads through the queue links (P-13), nothing is
// validated (P-14), and a slot's state is a function of its most recent init.

#pragma once

#include <cstdint>
#include <vector>

namespace io::github::giovanicaprison::matching::lean::flyweight {

class Slab {
 public:
  explicit Slab(const std::int32_t preallocated) : capacity_(preallocated) {
    cells_.assign(static_cast<std::size_t>(capacity_) << STRIDE_SHIFT, 0);
    thread(1);
  }

  // Growth is paid on the way up to the high-water mark; the steady state after it allocates
  // nothing (NFR-4.3), and every slot index stays valid across it.
  std::int32_t acquire() {
    if (freeHead_ == 0) {
      const std::int32_t first = capacity_;
      capacity_ <<= 1;
      cells_.resize(static_cast<std::size_t>(capacity_) << STRIDE_SHIFT, 0);
      thread(first);
    }
    const std::int32_t slot = freeHead_;
    freeHead_ = next(slot);
    cells_[at(slot, LINKS)] = 0;
    return slot;
  }

  // The slot must already be out of every chain (P-13); only the link word is rewritten.
  void release(const std::int32_t slot) {
    cells_[at(slot, LINKS)] = static_cast<std::uint32_t>(freeHead_);
    freeHead_ = slot;
  }

  // A fresh life for a slot: every field is written, nothing survives the last one.
  void init(const std::int32_t slot, const std::uint64_t id, const std::uint64_t clientOrderId,
            const std::uint32_t participantId, const std::int32_t side, const std::int32_t pricing,
            const std::int32_t timeInForce, const std::int32_t tick, const std::int64_t quantity,
            const std::int64_t arrival, const std::int64_t executed) {
    const std::size_t base = at(slot, 0);
    cells_[base + REMAINING] = quantity;
    cells_[base + EXECUTED] = executed;
    cells_[base + ID] = static_cast<std::int64_t>(id);
    cells_[base + LINKS] = 0;
    cells_[base + TICK] = tick;
    cells_[base + ARRIVAL] = arrival;
    cells_[base + CLIENT] = static_cast<std::int64_t>(clientOrderId);
    cells_[base + META] = static_cast<std::int64_t>(
        static_cast<std::uint64_t>(participantId) | (static_cast<std::uint64_t>(side) << 32) |
        (static_cast<std::uint64_t>(pricing) << 33) |
        (static_cast<std::uint64_t>(timeInForce) << 34));
  }

  // What is left is what is shown. Without icebergs the two are the same number.
  std::int64_t remaining(const std::int32_t slot) const { return cells_[at(slot, REMAINING)]; }

  // What has traded across the order's whole life, which a replace works its remainder from.
  std::int64_t executed(const std::int32_t slot) const { return cells_[at(slot, EXECUTED)]; }

  std::uint64_t id(const std::int32_t slot) const {
    return static_cast<std::uint64_t>(cells_[at(slot, ID)]);
  }

  std::int32_t tick(const std::int32_t slot) const {
    return static_cast<std::int32_t>(cells_[at(slot, TICK)]);
  }

  std::int64_t arrival(const std::int32_t slot) const { return cells_[at(slot, ARRIVAL)]; }

  std::uint64_t clientOrderId(const std::int32_t slot) const {
    return static_cast<std::uint64_t>(cells_[at(slot, CLIENT)]);
  }

  std::uint32_t participantId(const std::int32_t slot) const {
    return static_cast<std::uint32_t>(cells_[at(slot, META)]);
  }

  std::int32_t side(const std::int32_t slot) const {
    return static_cast<std::int32_t>(static_cast<std::uint64_t>(cells_[at(slot, META)]) >> 32) & 1;
  }

  std::int32_t pricing(const std::int32_t slot) const {
    return static_cast<std::int32_t>(static_cast<std::uint64_t>(cells_[at(slot, META)]) >> 33) & 1;
  }

  std::int32_t timeInForce(const std::int32_t slot) const {
    return static_cast<std::int32_t>(static_cast<std::uint64_t>(cells_[at(slot, META)]) >> 34) & 3;
  }

  std::int32_t next(const std::int32_t slot) const {
    return static_cast<std::int32_t>(static_cast<std::uint64_t>(cells_[at(slot, LINKS)]) &
                                     0xFFFFFFFF);
  }

  std::int32_t previous(const std::int32_t slot) const {
    return static_cast<std::int32_t>(static_cast<std::uint64_t>(cells_[at(slot, LINKS)]) >> 32);
  }

  void link(const std::int32_t slot, const std::int32_t previous, const std::int32_t next) {
    cells_[at(slot, LINKS)] = static_cast<std::int64_t>(
        (static_cast<std::uint64_t>(previous) << 32) | static_cast<std::uint32_t>(next));
  }

  void linkNext(const std::int32_t slot, const std::int32_t next) {
    const std::size_t word = at(slot, LINKS);
    cells_[word] = static_cast<std::int64_t>(
        (static_cast<std::uint64_t>(cells_[word]) & 0xFFFFFFFF00000000ULL) |
        static_cast<std::uint32_t>(next));
  }

  void linkPrevious(const std::int32_t slot, const std::int32_t previous) {
    const std::size_t word = at(slot, LINKS);
    cells_[word] =
        static_cast<std::int64_t>((static_cast<std::uint64_t>(cells_[word]) & 0xFFFFFFFFULL) |
                                  (static_cast<std::uint64_t>(previous) << 32));
  }

  void take(const std::int32_t slot, const std::int64_t quantity) {
    const std::size_t base = at(slot, 0);
    cells_[base + REMAINING] -= quantity;
    cells_[base + EXECUTED] += quantity;
  }

  void rest(const std::int32_t slot, const std::int64_t arrivalSequence) {
    cells_[at(slot, ARRIVAL)] = arrivalSequence;
  }

  // A replace that keeps queue position (FR-4.4) changes what is left and nothing else.
  void reduceTo(const std::int32_t slot, const std::int64_t remainder) {
    cells_[at(slot, REMAINING)] = remainder;
  }

 private:
  static constexpr std::size_t REMAINING = 0;
  static constexpr std::size_t EXECUTED = 1;
  static constexpr std::size_t ID = 2;
  // Previous in the high 32 bits, next in the low 32, zero meaning end of chain.
  static constexpr std::size_t LINKS = 3;
  static constexpr std::size_t TICK = 4;
  static constexpr std::size_t ARRIVAL = 5;
  static constexpr std::size_t CLIENT = 6;
  // Participant in the low 32 bits, then side, pricing and time in force.
  static constexpr std::size_t META = 7;

  static constexpr std::size_t STRIDE_SHIFT = 3;

  static std::size_t at(const std::int32_t slot, const std::size_t field) {
    return (static_cast<std::size_t>(slot) << STRIDE_SHIFT) + field;
  }

  void thread(const std::int32_t first) {
    for (std::int32_t slot = capacity_ - 1; slot >= first; slot--) {
      cells_[at(slot, LINKS)] = static_cast<std::uint32_t>(freeHead_);
      freeHead_ = slot;
    }
  }

  std::vector<std::int64_t> cells_;
  std::int32_t capacity_;
  std::int32_t freeHead_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::lean::flyweight

// Every order the venue holds, as slots in one preallocated array of 64 bit words, at a layout
// matched word for word to the Java rung (P-17). An order is an int slot: a field access is an
// array read at a computed offset, a queue link is an int in the same stride, and the free list
// threads through the links the queues use, so a slot is always in exactly one chain (P-13). Slot
// zero is reserved as the null link, which makes a freshly zeroed array already empty.
//
// The stride is sixteen words, 128 bytes, two cache lines, split hot and cold exactly as the twin
// splits them: the first line carries what the take loop touches per fill, the second carries
// identity and the entry-time qualifiers, read once per command at most. Nothing is validated
// (P-14) and nothing is cleared on release beyond the link word, so a released slot still answers
// reads with its final values until it is reissued, which the uncrossing relies on. A slot's state
// is a function of its most recent init (P-13).

#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

namespace io::github::giovanicaprison::matching::flyweight {

class Slab {
 public:
  explicit Slab(const std::int32_t preallocated) : capacity_(preallocated) {
    cells_.assign(static_cast<std::size_t>(capacity_) << STRIDE_SHIFT, 0);
    thread(1);
  }

  // A slot to wear the next order. Growth doubles the slab and is paid on the way up to the
  // high-water mark of live orders, so the steady state after it allocates nothing (NFR-4.3), and
  // every slot index stays valid across it because an index is not an address.
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
            const std::int32_t timeInForce, const bool postOnly, const std::int32_t tick,
            const std::int64_t quantity, const std::int64_t minQuantity,
            const std::int64_t displayQuantity, const std::int64_t triggerPrice,
            const std::uint64_t smpId, const std::int64_t arrival, const std::int64_t executed) {
    const std::size_t base = at(slot, 0);
    cells_[base + REMAINING] = quantity;
    cells_[base + DISPLAYED] =
        displayQuantity == 0 ? quantity : std::min(displayQuantity, quantity);
    cells_[base + EXECUTED] = executed;
    cells_[base + ID] = static_cast<std::int64_t>(id);
    cells_[base + LINKS] = 0;
    cells_[base + TICK] = tick;
    cells_[base + ARRIVAL] = arrival;
    cells_[base + SMP] = static_cast<std::int64_t>(smpId);
    cells_[base + CLIENT] = static_cast<std::int64_t>(clientOrderId);
    cells_[base + META] = static_cast<std::int64_t>(
        static_cast<std::uint64_t>(participantId) | (static_cast<std::uint64_t>(side) << 32) |
        (static_cast<std::uint64_t>(pricing) << 33) |
        (static_cast<std::uint64_t>(timeInForce) << 34) | (postOnly ? std::uint64_t{1} << 36 : 0));
    cells_[base + MIN_QUANTITY] = minQuantity;
    cells_[base + TRIGGER] = triggerPrice;
    cells_[base + DISPLAY_SIZE] = displayQuantity;
  }

  std::int64_t remaining(const std::int32_t slot) const { return cells_[at(slot, REMAINING)]; }

  // What the feed has been told about, which is never the hidden part (FR-5.2).
  std::int64_t displayed(const std::int32_t slot) const { return cells_[at(slot, DISPLAYED)]; }

  // How much of this order has traded, over its whole life and across every replace, which is
  // what a replace's remainder is worked out from (FR-4.9).
  std::int64_t executed(const std::int32_t slot) const { return cells_[at(slot, EXECUTED)]; }

  std::uint64_t id(const std::int32_t slot) const {
    return static_cast<std::uint64_t>(cells_[at(slot, ID)]);
  }

  std::int32_t tick(const std::int32_t slot) const {
    return static_cast<std::int32_t>(cells_[at(slot, TICK)]);
  }

  std::int64_t arrival(const std::int32_t slot) const { return cells_[at(slot, ARRIVAL)]; }

  std::uint64_t smpId(const std::int32_t slot) const {
    return static_cast<std::uint64_t>(cells_[at(slot, SMP)]);
  }

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

  bool postOnly(const std::int32_t slot) const {
    return (static_cast<std::uint64_t>(cells_[at(slot, META)]) & (std::uint64_t{1} << 36)) != 0;
  }

  std::int64_t minQuantity(const std::int32_t slot) const { return cells_[at(slot, MIN_QUANTITY)]; }

  std::int64_t triggerPrice(const std::int32_t slot) const { return cells_[at(slot, TRIGGER)]; }

  // The tranche size an iceberg shows at a time, which a replace has to preserve (FR-4.10).
  std::int64_t displaySize(const std::int32_t slot) const { return cells_[at(slot, DISPLAY_SIZE)]; }

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

  // Takes quantity from the displayed part first, since that is all a taker can see. Returns
  // whether the displayed part is now empty while quantity remains, which is when a further
  // tranche is displayed and joins the back of its queue (FR-5.4).
  bool take(const std::int32_t slot, const std::int64_t quantity) {
    const std::size_t base = at(slot, 0);
    cells_[base + REMAINING] -= quantity;
    cells_[base + EXECUTED] += quantity;
    cells_[base + DISPLAYED] -= quantity;
    return cells_[base + DISPLAYED] == 0 && cells_[base + REMAINING] > 0;
  }

  // This slot is joining the queue at its price: what it shows and where it stands are settled
  // now, so an order that crossed on the way in queues behind everything that joined while it was
  // walking (FR-2.7), and a replenished tranche does the same (FR-5.4).
  void rest(const std::int32_t slot, const std::int64_t arrivalSequence) {
    const std::size_t base = at(slot, 0);
    const std::int64_t size = cells_[base + DISPLAY_SIZE];
    const std::int64_t remaining = cells_[base + REMAINING];
    cells_[base + DISPLAYED] = size == 0 ? remaining : std::min(size, remaining);
    cells_[base + ARRIVAL] = arrivalSequence;
  }

  // A replace that keeps queue position (FR-4.4) changes what is left and nothing else.
  void reduceTo(const std::int32_t slot, const std::int64_t remainder) {
    const std::size_t base = at(slot, 0);
    const std::int64_t size = cells_[base + DISPLAY_SIZE];
    cells_[base + REMAINING] = remainder;
    cells_[base + DISPLAYED] = size == 0 ? remainder : std::min(size, remainder);
  }

  // A triggered stop becomes an ordinary order of its own pricing instruction (FR-6.3), in place,
  // because it is the same order and a fresh slot would carry no new information.
  void triggered(const std::int32_t slot, const std::int64_t arrivalSequence) {
    const std::size_t base = at(slot, 0);
    cells_[base + TRIGGER] = 0;
    cells_[base + ARRIVAL] = arrivalSequence;
  }

  // A stop rests in the trigger book and is not book liquidity (FR-6.1).
  bool stop(const std::int32_t slot) const { return cells_[at(slot, TRIGGER)] != 0; }

 private:
  // Hot line: what one execution touches, kept within one 64 byte stretch of the slot.
  static constexpr std::size_t REMAINING = 0;
  static constexpr std::size_t DISPLAYED = 1;
  static constexpr std::size_t EXECUTED = 2;
  static constexpr std::size_t ID = 3;
  // Previous in the high 32 bits, next in the low 32, zero meaning end of chain.
  static constexpr std::size_t LINKS = 4;
  static constexpr std::size_t TICK = 5;
  static constexpr std::size_t ARRIVAL = 6;
  static constexpr std::size_t SMP = 7;

  // Cold line: identity and entry-time qualifiers, touched once per command at most.
  static constexpr std::size_t CLIENT = 8;
  // Participant in the low 32 bits, then side, pricing, time in force and the post-only bit.
  static constexpr std::size_t META = 9;
  static constexpr std::size_t MIN_QUANTITY = 10;
  static constexpr std::size_t TRIGGER = 11;
  static constexpr std::size_t DISPLAY_SIZE = 12;

  static constexpr std::size_t STRIDE_SHIFT = 4;

  static std::size_t at(const std::int32_t slot, const std::size_t field) {
    return (static_cast<std::size_t>(slot) << STRIDE_SHIFT) + field;
  }

  // Chains every slot from first up into the free list, newest acquisitions lowest.
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

}  // namespace io::github::giovanicaprison::matching::flyweight

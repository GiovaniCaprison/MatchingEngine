// The queue between two threads, one producer and one consumer, in the shape agrona's ring gives
// the Java side: length-prefixed typed records, eight byte alignment, a padding record where a
// message will not fit before the end, and claim-then-commit publication so a producer encodes in
// place. Visibility is gated by one released tail, so the consumer never sees a half-written
// record, and an extra copy never lands inside anybody's service time.

#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

namespace io::github::giovanicaprison::matching::benchmarks {

class SpscRing {
 public:
  static constexpr std::int32_t PADDING = -1;
  static constexpr std::size_t HEADER = 8;  // int32 length, int32 type
  static constexpr std::size_t ALIGNMENT = 8;

  explicit SpscRing(const std::size_t capacity) : buffer_(capacity), capacity_(capacity) {}

  // Space for one record, or a negative index when the ring is full. One claim outstanding at a
  // time, committed before the next; the producer's thread only.
  std::ptrdiff_t tryClaim(const std::int32_t type, const std::size_t length) {
    const std::size_t record = aligned(HEADER + length);
    if (record > available()) {
      return -1;
    }
    std::size_t index = pending_ & (capacity_ - 1);
    const std::size_t toEnd = capacity_ - index;
    if (record > toEnd) {
      // The record will not fit before the end, so padding fills the gap and the real record
      // starts at zero, with the space check covering both.
      if (record + toEnd > available()) {
        return -1;
      }
      header(index, static_cast<std::int32_t>(toEnd), PADDING);
      pending_ += toEnd;
      index = 0;
    }
    header(index, static_cast<std::int32_t>(HEADER + length), type);
    pending_ += record;
    return static_cast<std::ptrdiff_t>(index + HEADER);
  }

  // Publishes everything claimed. The record is the consumer's to read from here.
  void commit() { tail_.store(pending_, std::memory_order_release); }

  // Where claims land. The same buffer for the life of the ring, so a producer may hold it.
  char* buffer() { return buffer_.data(); }

  // One record in by copy, which is claim, write and commit for a producer that has the bytes.
  bool write(const std::int32_t type, const char* source, const std::size_t length) {
    const std::ptrdiff_t index = tryClaim(type, length);
    if (index < 0) {
      return false;
    }
    if (length > 0) {
      std::memcpy(buffer_.data() + index, source, length);
    }
    commit();
    return true;
  }

  // Up to limit records out, each handed to the handler in place. The consumer's thread only.
  template <typename Handler>
  std::size_t read(Handler&& handler, const std::size_t limit) {
    std::size_t consumed = 0;
    std::size_t head = head_.load(std::memory_order_relaxed);
    const std::size_t tail = tail_.load(std::memory_order_acquire);
    while (consumed < limit && head < tail) {
      const std::size_t index = head & (capacity_ - 1);
      std::int32_t length;
      std::int32_t type;
      std::memcpy(&length, buffer_.data() + index, sizeof(length));
      std::memcpy(&type, buffer_.data() + index + 4, sizeof(type));
      if (type == PADDING) {
        head += static_cast<std::size_t>(length);
        continue;
      }
      handler(type, buffer_.data() + index + HEADER, static_cast<std::size_t>(length) - HEADER);
      head += aligned(static_cast<std::size_t>(length));
      consumed++;
    }
    head_.store(head, std::memory_order_release);
    return consumed;
  }

  // How many bytes sit between the two threads, for the high water record.
  std::size_t queued() const {
    return tail_.load(std::memory_order_relaxed) - head_.load(std::memory_order_relaxed);
  }

 private:
  static std::size_t aligned(const std::size_t length) {
    return (length + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
  }

  std::size_t available() const {
    return capacity_ - (pending_ - head_.load(std::memory_order_acquire));
  }

  void header(const std::size_t index, const std::int32_t length, const std::int32_t type) {
    std::memcpy(buffer_.data() + index, &length, sizeof(length));
    std::memcpy(buffer_.data() + index + 4, &type, sizeof(type));
  }

  std::vector<char> buffer_;
  const std::size_t capacity_;
  std::size_t pending_ = 0;  // The producer's own tail, published on commit.
  alignas(64) std::atomic<std::size_t> tail_{0};
  alignas(64) std::atomic<std::size_t> head_{0};
};

}  // namespace io::github::giovanicaprison::matching::benchmarks

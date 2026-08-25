// Hardware counters for the thread that opened them, read around a region rather than sampled,
// mirroring the Java implementation call for call: the same perf_event_open syscall numbers, the
// same exclude bits, the same read format with the enabled and running times, and the same lesson
// the first shared runner taught, that availability is learned by opening a counter rather than by
// finding symbols.

#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

#if defined(__linux__)
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace io::github::giovanicaprison::matching::benchmarks {

// The catalogue, mirroring the Java enum: names, families and configs.
enum class Counter {
  CYCLES,
  INSTRUCTIONS,
  CACHE_REFERENCES,
  CACHE_MISSES,
  BRANCH_INSTRUCTIONS,
  BRANCH_MISSES,
  STALLED_CYCLES_FRONTEND,
  STALLED_CYCLES_BACKEND,
  L1D_READ_MISSES,
  DTLB_READ_MISSES,
};

namespace counters {

struct Event {
  std::uint32_t type;
  std::uint64_t config;
  const char* name;
};

inline Event eventOf(const Counter counter) {
  constexpr auto cache = [](const std::uint64_t id, const std::uint64_t op,
                            const std::uint64_t result) { return id | op << 8 | result << 16; };
  switch (counter) {
    case Counter::CYCLES:
      return {0, 0, "CYCLES"};
    case Counter::INSTRUCTIONS:
      return {0, 1, "INSTRUCTIONS"};
    case Counter::CACHE_REFERENCES:
      return {0, 2, "CACHE_REFERENCES"};
    case Counter::CACHE_MISSES:
      return {0, 3, "CACHE_MISSES"};
    case Counter::BRANCH_INSTRUCTIONS:
      return {0, 4, "BRANCH_INSTRUCTIONS"};
    case Counter::BRANCH_MISSES:
      return {0, 5, "BRANCH_MISSES"};
    case Counter::STALLED_CYCLES_FRONTEND:
      return {0, 7, "STALLED_CYCLES_FRONTEND"};
    case Counter::STALLED_CYCLES_BACKEND:
      return {0, 8, "STALLED_CYCLES_BACKEND"};
    case Counter::L1D_READ_MISSES:
      return {3, cache(0, 0, 1), "L1D_READ_MISSES"};
    case Counter::DTLB_READ_MISSES:
      return {3, cache(3, 0, 1), "DTLB_READ_MISSES"};
  }
  return {0, 0, "?"};
}

// The four that fit any processor's slots, which is what a standard run carries.
inline std::vector<Counter> few() {
  return {Counter::CYCLES, Counter::INSTRUCTIONS, Counter::CACHE_MISSES, Counter::BRANCH_MISSES};
}

inline std::optional<Counter> byName(const std::string& name) {
  for (const Counter counter :
       {Counter::CYCLES, Counter::INSTRUCTIONS, Counter::CACHE_REFERENCES, Counter::CACHE_MISSES,
        Counter::BRANCH_INSTRUCTIONS, Counter::BRANCH_MISSES, Counter::STALLED_CYCLES_FRONTEND,
        Counter::STALLED_CYCLES_BACKEND, Counter::L1D_READ_MISSES, Counter::DTLB_READ_MISSES}) {
    if (name == eventOf(counter).name) {
      return counter;
    }
  }
  return std::nullopt;
}

}  // namespace counters

class Counters {
 public:
  struct Sample {
    std::int64_t value = -1;
    std::int64_t enabled = 0;
    std::int64_t running = 0;

    bool readable() const { return value >= 0; }
    bool multiplexed() const { return readable() && running < enabled; }
  };

  struct Reading {
    std::map<Counter, Sample> samples;

    std::map<Counter, std::int64_t> since(const Reading& earlier) const {
      std::map<Counter, std::int64_t> counted;
      for (const auto& [counter, sample] : samples) {
        const auto before = earlier.samples.find(counter);
        if (before != earlier.samples.end() && sample.readable() && before->second.readable()) {
          counted[counter] = sample.value - before->second.value;
        }
      }
      return counted;
    }

    bool multiplexed() const {
      for (const auto& [counter, sample] : samples) {
        if (sample.multiplexed()) {
          return true;
        }
      }
      return false;
    }
  };

  // Whether counters can actually be opened, learned by opening one rather than by platform name.
  static bool available() {
    static const bool opens = probe();
    return opens;
  }

  static std::optional<Counters> open(const std::vector<Counter>& wanted) {
    if (!available() || wanted.empty()) {
      return std::nullopt;
    }
    Counters counters;
    for (const Counter counter : wanted) {
      const int descriptor = perfEventOpen(counters::eventOf(counter));
      if (descriptor < 0) {
        counters.close();
        return std::nullopt;
      }
      counters.descriptors_.emplace_back(counter, descriptor);
    }
    return counters;
  }

  Reading read() const {
    Reading reading;
    for (const auto& [counter, descriptor] : descriptors_) {
      reading.samples[counter] = sampleOf(descriptor);
    }
    return reading;
  }

  void close() {
#if defined(__linux__)
    for (const auto& [counter, descriptor] : descriptors_) {
      ::close(descriptor);
    }
#endif
    descriptors_.clear();
  }

 private:
  static bool probe() {
#if defined(__linux__)
    const int descriptor = perfEventOpen(counters::eventOf(Counter::INSTRUCTIONS));
    if (descriptor < 0) {
      return false;
    }
    ::close(descriptor);
    return true;
#else
    return false;
#endif
  }

#if defined(__linux__)
  // perf_event_attr laid out by hand, exactly as the Java side does through the foreign function
  // API: 128 zeroed bytes, type at 0, size at 4, config at 8, read_format at 32, and the exclude
  // kernel and hypervisor bits at 40.
  static int perfEventOpen(const counters::Event event) {
    alignas(8) unsigned char attributes[128] = {};
    *reinterpret_cast<std::uint32_t*>(attributes + 0) = event.type;
    *reinterpret_cast<std::uint32_t*>(attributes + 4) = 128;
    *reinterpret_cast<std::uint64_t*>(attributes + 8) = event.config;
    *reinterpret_cast<std::uint64_t*>(attributes + 32) = 3;     // enabled and running times
    *reinterpret_cast<std::uint64_t*>(attributes + 40) = 0x60;  // exclude kernel and hypervisor
    return static_cast<int>(::syscall(SYS_perf_event_open, attributes, 0, -1, -1, 0UL));
  }

  static Sample sampleOf(const int descriptor) {
    std::int64_t values[3] = {0, 0, 0};
    if (::read(descriptor, values, sizeof(values)) != sizeof(values)) {
      return Sample{};
    }
    return Sample{values[0], values[1], values[2]};
  }
#else
  static int perfEventOpen(const counters::Event) { return -1; }
  static Sample sampleOf(const int) { return Sample{}; }
#endif

  std::vector<std::pair<Counter, int>> descriptors_;
};

}  // namespace io::github::giovanicaprison::matching::benchmarks

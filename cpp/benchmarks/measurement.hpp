// One run: a driver offering commands at a fixed rate, an engine applying them, and a verifier
// checking what came out, mirroring the Java harness thread for thread. Open loop, so a stall
// shows up as a queue rather than as samples nobody took; each thread pins itself before it does
// anything else, and the pin is read back rather than assumed; counters bracket the reported
// region on the engine's own thread; and the machine is sampled before and after with the engine
// thread reading its own switch counts at the edges of its work.

#pragma once

#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "api/event_publisher.hpp"
#include "api/matching_engine.hpp"
#include "benchmarks/affinity.hpp"
#include "benchmarks/counters.hpp"
#include "benchmarks/json.hpp"
#include "benchmarks/sample.hpp"
#include "benchmarks/setting.hpp"
#include "benchmarks/spsc_ring.hpp"
#include "benchmarks/timings.hpp"
#include "benchmarks/verification.hpp"
#include "conformance/command_log.hpp"

namespace io::github::giovanicaprison::matching::benchmarks {

struct Cores {
  static constexpr int UNPINNED = -1;
  int driver = UNPINNED;
  int engine = UNPINNED;
  int verifier = UNPINNED;
};

struct Parameters {
  std::int64_t ratePerSecond = 100'000;
  std::size_t compilationWarmup = 200'000;
  std::size_t inputRing = 1 << 24;
  std::size_t outputRing = 1 << 24;
  Cores cores;
  std::vector<Counter> counters = counters::few();
};

class Measurement {
 public:
  using EngineFactory = std::function<std::unique_ptr<api::MatchingEngine>(api::EventPublisher&)>;

  struct Outcome {
    std::size_t commands = 0;
    Timings::Summary service;
    Timings::Summary response;
    Timings::Summary offered;
    VerificationRecord verification;
    std::vector<Setting> placement;
    std::vector<Setting> sampledBefore;
    std::vector<Setting> sampledAfter;
    std::map<Counter, std::int64_t> counted;
    bool countersMultiplexed = false;
    std::uint64_t publishRetries = 0;
    std::uint64_t publisherWaits = 0;
    std::int64_t publisherWaitedNanos = 0;
    std::size_t commandsQueuedHighWater = 0;
    std::size_t eventsQueuedHighWater = 0;

    bool harnessKeptUp() const { return publishRetries == 0 && publisherWaits == 0; }

    bool placedAsAsked() const {
      for (const Setting& setting : placement) {
        if (!setting.satisfied()) {
          return false;
        }
      }
      return true;
    }

    std::string toJson() const;
    void writeTo(const std::filesystem::path& directory) const;

    std::shared_ptr<const Timings> timings;
  };

  static Outcome run(const conformance::CommandLog& log, const EngineFactory& factory,
                     const Parameters& parameters) {
    Measurement measurement(log, parameters);
    return measurement.execute(factory);
  }

 private:
  static constexpr std::int32_t EVENT = 1;
  static constexpr std::int32_t END = 2;
  static constexpr std::int32_t COMMAND = 3;

  // The engine writes events straight into the ring's own memory, exactly as the Java engine
  // claims agrona's, so no copy sits inside anybody's service time. A full ring is back pressure:
  // claim waits, counts the waits, and a run that waited says so.
  class RingEventPublisher final : public api::EventPublisher {
   public:
    explicit RingEventPublisher(SpscRing& events) : events_(events) {}

    std::size_t claim(const std::size_t length) override {
      std::ptrdiff_t index = events_.tryClaim(EVENT, length);
      if (index < 0) {
        const auto began = std::chrono::steady_clock::now();
        while ((index = events_.tryClaim(EVENT, length)) < 0) {
        }
        waits_++;
        waitedNanos_ += std::chrono::duration_cast<std::chrono::nanoseconds>(
                            std::chrono::steady_clock::now() - began)
                            .count();
      }
      return static_cast<std::size_t>(index);
    }

    char* buffer() override { return events_.buffer(); }

    void commit() override { events_.commit(); }

    std::uint64_t waits() const { return waits_; }
    std::int64_t waitedNanos() const { return waitedNanos_; }

   private:
    SpscRing& events_;
    std::uint64_t waits_ = 0;
    std::int64_t waitedNanos_ = 0;
  };

  Measurement(const conformance::CommandLog& log, const Parameters& parameters)
      : log_(log),
        parameters_(parameters),
        commands_(parameters.inputRing),
        events_(parameters.outputRing),
        publisher_(events_),
        timings_(std::make_shared<Timings>(
            log.count() - log.measuredFrom,
            std::min(parameters.compilationWarmup, log.count() - log.measuredFrom))) {}

  Outcome execute(const EngineFactory& factory) {
    const std::unique_ptr<api::MatchingEngine> engine = factory(publisher_);
    sampledBefore_ = sample::ofCore(machine_, parameters_.cores.engine);
    std::thread verifier(
        [this] { pinned("verifier", parameters_.cores.verifier, [this] { verify(); }); });
    std::thread runner([this, &engine] {
      pinned("engine", parameters_.cores.engine, [this, &engine] { apply(*engine); });
    });
    pinned("driver", parameters_.cores.driver, [this] { drive(); });
    runner.join();
    verifier.join();
    const std::vector<Setting> after = sample::ofCore(machine_, parameters_.cores.engine);
    sampledAfter_.insert(sampledAfter_.end(), after.begin(), after.end());
    return outcome();
  }

  void drive() {
    const std::int64_t period = 1'000'000'000LL / parameters_.ratePerSecond;
    const std::int64_t begin = now();
    const std::size_t measuredFrom = log_.measuredFrom;
    for (std::size_t command = 0; command < log_.count(); command++) {
      const std::int64_t intendedAt = begin + static_cast<std::int64_t>(command) * period;
      while (now() < intendedAt) {
      }
      const bool measured = command >= measuredFrom;
      if (measured) {
        timings_->intended(command - measuredFrom, intendedAt);
      }
      while (!commands_.write(COMMAND, log_.buffer.data() + log_.offsets[command],
                              log_.lengths[command])) {
        publishRetries_++;
      }
      if (measured) {
        timings_->published(command - measuredFrom, now());
      }
      commandsQueued_ = std::max(commandsQueued_, commands_.queued());
    }
  }

  void apply(api::MatchingEngine& engine) {
    const std::vector<Setting> before = sample::ofThisThread(machine_);
    sampledBefore_.insert(sampledBefore_.end(), before.begin(), before.end());
    const std::size_t measuredFrom = log_.measuredFrom;
    const auto handler = [this, &engine, measuredFrom](const std::int32_t, char* buffer,
                                                       const std::size_t length) {
      const bool measured = applied_ >= measuredFrom;
      const std::int64_t from = now();
      engine.onCommand(buffer, 0, length);
      const std::int64_t to = now();
      if (measured) {
        timings_->record(applied_ - measuredFrom, from, to);
      }
      applied_++;
    };
    const std::size_t reportFrom =
        std::min(log_.count(), measuredFrom + parameters_.compilationWarmup);
    applyUntil(handler, reportFrom);
    // The reported region, with the counters open across it and nothing else: opened here because
    // counting is per thread, and this is the thread.
    auto open = Counters::open(parameters_.counters);
    const auto beforeCounts = open.has_value() ? open->read() : Counters::Reading{};
    applyUntil(handler, log_.count());
    if (open.has_value()) {
      const auto afterCounts = open->read();
      counted_ = afterCounts.since(beforeCounts);
      countersMultiplexed_ = afterCounts.multiplexed();
      open->close();
    }
    const std::vector<Setting> after = sample::ofThisThread(machine_);
    sampledAfter_.insert(sampledAfter_.end(), after.begin(), after.end());
    while (!events_.write(END, nullptr, 0)) {
    }
  }

  template <typename Handler>
  void applyUntil(Handler&& handler, const std::size_t bound) {
    while (applied_ < bound) {
      commands_.read(handler, 1);
    }
  }

  void verify() {
    bool ended = false;
    const auto handler = [this, &ended](const std::int32_t type, char* buffer,
                                        const std::size_t length) {
      if (type == END) {
        ended = true;
      } else {
        verification_.record(buffer, length);
      }
    };
    while (!ended) {
      events_.read(handler, 64);
      eventsQueued_ = std::max(eventsQueued_, events_.queued());
    }
  }

  void pinned(const std::string& name, const int core, const std::function<void()>& work) {
    if (core != Cores::UNPINNED) {
      const Setting setting = affinity::pin(name, core);
      placementLock_.lock();
      placement_.push_back(setting);
      placementLock_.unlock();
    }
    work();
  }

  Outcome outcome() {
    Outcome out;
    out.commands = applied_;
    out.timings = timings_;
    out.service = timings_->service();
    out.response = timings_->response();
    out.offered = timings_->offered();
    out.verification = verification_;
    out.placement = placement_;
    out.sampledBefore = sampledBefore_;
    out.sampledAfter = sampledAfter_;
    out.counted = counted_;
    out.countersMultiplexed = countersMultiplexed_;
    out.publishRetries = publishRetries_;
    out.publisherWaits = publisher_.waits();
    out.publisherWaitedNanos = publisher_.waitedNanos();
    out.commandsQueuedHighWater = commandsQueued_;
    out.eventsQueuedHighWater = eventsQueued_;
    return out;
  }

  static std::int64_t now() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
  }

  const conformance::CommandLog& log_;
  const Parameters& parameters_;
  SpscRing commands_;
  SpscRing events_;
  RingEventPublisher publisher_;
  std::shared_ptr<Timings> timings_;
  VerificationRecord verification_;
  std::filesystem::path machine_ = "/";

  struct SpinLock {
    std::atomic_flag flag;
    void lock() {
      while (flag.test_and_set(std::memory_order_acquire)) {
      }
    }
    void unlock() { flag.clear(std::memory_order_release); }
  } placementLock_;

  std::vector<Setting> placement_;
  std::vector<Setting> sampledBefore_;
  std::vector<Setting> sampledAfter_;
  std::map<Counter, std::int64_t> counted_;
  bool countersMultiplexed_ = false;
  std::uint64_t publishRetries_ = 0;
  std::size_t commandsQueued_ = 0;
  std::size_t eventsQueued_ = 0;
  std::size_t applied_ = 0;
};

inline std::string Measurement::Outcome::toJson() const {
  Json json;
  json.object()
      .field("commands", static_cast<std::uint64_t>(commands))
      .field("harnessKeptUp", harnessKeptUp())
      .field("placedAsAsked", placedAsAsked())
      .field("publishRetries", publishRetries)
      .field("publisherWaits", publisherWaits)
      .field("publisherWaitedNanos", publisherWaitedNanos)
      .field("commandsQueuedHighWater", static_cast<std::uint64_t>(commandsQueuedHighWater))
      .field("eventsQueuedHighWater", static_cast<std::uint64_t>(eventsQueuedHighWater))
      .field("events", verification.events())
      .field("digest", [this] {
        std::string hex;
        for (int shift = 60; shift >= 0; shift -= 4) {
          const char digit = "0123456789abcdef"[(verification.digest() >> shift) & 0xF];
          if (!hex.empty() || digit != '0' || shift == 0) {
            hex += digit;
          }
        }
        return hex;
      }());
  json.object("counts");
  for (const auto& [name, count] : verification.countsByName()) {
    json.field(name, count);
  }
  json.end();
  json.object("counters").field("multiplexed", countersMultiplexed);
  for (const auto& [counter, value] : counted) {
    json.field(counters::eventOf(counter).name, value);
  }
  json.end();
  const auto settings = [&json](const std::string& name, const std::vector<Setting>& values) {
    json.array(name);
    for (const Setting& setting : values) {
      setting.writeTo(json);
    }
    json.end();
  };
  settings("placement", placement);
  settings("sampledBefore", sampledBefore);
  settings("sampledAfter", sampledAfter);
  const auto summary = [&json](const std::string& name, const Timings::Summary& values) {
    json.object(name)
        .field("count", values.count)
        .field("p50", values.p50)
        .field("p99", values.p99)
        .field("p999", values.p999)
        .field("max", values.max)
        .end();
  };
  summary("service", service);
  summary("response", response);
  json.end();
  return json.done();
}

inline void Measurement::Outcome::writeTo(const std::filesystem::path& directory) const {
  std::ofstream measurement(directory / "measurement.json");
  measurement << toJson();
  verification.writeTo((directory / "verification.json").string());
  timings->writeTimings((directory / "timings.bin").string());
}

}  // namespace io::github::giovanicaprison::matching::benchmarks

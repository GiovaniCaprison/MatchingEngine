// The harness primitives in this language, held to the same claims the Java suite holds its own:
// the ring loses nothing and reorders nothing, the digest is the same FNV-1a both languages
// compute, the probes read a machine out of a directory, and a whole measurement runs an engine
// end to end with every command applied once and every event accounted for.

#include <catch2/catch_test_macros.hpp>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <random>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "benchmarks/environment.hpp"
#include "benchmarks/measurement.hpp"
#include "benchmarks/spsc_ring.hpp"
#include "benchmarks/timings.hpp"
#include "benchmarks/verification.hpp"
#include "conformance/command_log.hpp"
#include "conformance/command_writer.hpp"
#include "conformance/fixture_parser.hpp"
#include "conformance/references.hpp"
#include "naive/naive_engine.hpp"

using namespace io::github::giovanicaprison::matching;
namespace benchmarks = io::github::giovanicaprison::matching::benchmarks;

TEST_CASE("the ring carries every record across two threads, in order, wraps included") {
  benchmarks::SpscRing ring(1 << 10);
  constexpr int MESSAGES = 200'000;

  std::thread producer([&ring] {
    // NOLINTNEXTLINE(bugprone-random-generator-seed): a test wants the same sizes every run.
    std::mt19937 sizes(7);
    for (int at = 0; at < MESSAGES; at++) {
      char payload[64];
      const std::size_t length = 8 + sizes() % 48;
      std::memcpy(payload, &at, sizeof(at));
      while (!ring.write(1, payload, length)) {
      }
    }
  });

  int seen = 0;
  bool ordered = true;
  while (seen < MESSAGES) {
    ring.read(
        [&seen, &ordered](const std::int32_t, char* payload, const std::size_t) {
          int at;
          std::memcpy(&at, payload, sizeof(at));
          ordered = ordered && at == seen;
          seen++;
        },
        64);
  }
  producer.join();
  CHECK(seen == MESSAGES);
  CHECK(ordered);
}

TEST_CASE("a full ring refuses rather than overwriting") {
  benchmarks::SpscRing ring(64);
  const char payload[16] = {};
  CHECK(ring.write(1, payload, sizeof(payload)));
  CHECK(ring.write(1, payload, sizeof(payload)));
  CHECK_FALSE(ring.write(1, payload, sizeof(payload)));
}

TEST_CASE("the digest is the FNV-1a both languages compute") {
  // The published vector for one byte, so a wrong constant on either side fails loudly.
  const char a = 'a';
  CHECK(benchmarks::VerificationRecord::hash(benchmarks::VerificationRecord::FNV_OFFSET, &a, 1) ==
        0xaf63dc4c8601ec8cULL);
}

TEST_CASE("the probes read a machine out of a directory") {
  const std::filesystem::path machine =
      std::filesystem::temp_directory_path() / "matching-harness-test-machine";
  std::filesystem::remove_all(machine);
  const auto write = [&machine](const std::string& path, const std::string& content) {
    const std::filesystem::path file = machine / path;
    std::filesystem::create_directories(file.parent_path());
    std::ofstream out(file);
    out << content;
  };
  write("proc/cmdline", "quiet isolcpus=2-5 nohz_full=2-5 rcu_nocbs=2-5\n");
  write("sys/devices/system/cpu/cpu0/cpufreq/scaling_governor", "powersave\n");
  write("sys/devices/system/cpu/cpu4/topology/thread_siblings_list", "4\n");

  const benchmarks::Environment environment = benchmarks::Environment::reading(machine);

  CHECK_FALSE(environment.measurementGrade());
  const auto isolation = environment.isolationOf(4);
  CHECK(isolation.size() == 4);
  for (const benchmarks::Setting& setting : isolation) {
    INFO(setting.name);
    CHECK(setting.status == benchmarks::Setting::Status::OK);
  }
  const auto outside = environment.isolationOf(0);
  CHECK(outside.front().actual == std::optional<std::string>("false"));
}

TEST_CASE("a measurement applies every command once and accounts for every event") {
  // A log built from a fixture, which is how this side gets bytes without owning a generator.
  const conformance::Fixture fixture = conformance::parseFixture(
      "harness",
      "INSTRUMENT tick=5 lot=1 scale=4 min=1 max=1000000 band=500000 open=100000 alloc=PRICE_TIME\n"
      "SESSION CONTINUOUS\n"
      "NEW BUY LIMIT GTC 100000 50\n"
      "NEW SELL LIMIT GTC 100005 30\n"
      "NEW SELL LIMIT IOC 100000 20\n"
      "CANCEL #2\n");
  conformance::References references;
  conformance::CommandWriter writer(references);
  conformance::CommandLog log;
  for (const conformance::Command& command : fixture.commands()) {
    const std::size_t length = writer.write(command);
    const std::size_t at = log.buffer.size();
    log.buffer.resize(at + length);
    std::memcpy(log.buffer.data() + at, writer.buffer(), length);
    log.offsets.push_back(at);
    log.lengths.push_back(length);
  }
  log.measuredFrom = 0;

  benchmarks::Parameters parameters;
  parameters.ratePerSecond = 100'000;
  parameters.compilationWarmup = 0;
  parameters.inputRing = 1 << 16;
  parameters.outputRing = 1 << 16;

  const benchmarks::Measurement::Outcome outcome = benchmarks::Measurement::run(
      log, [](api::EventPublisher& events) { return std::make_unique<naive::NaiveEngine>(events); },
      parameters);

  CHECK(outcome.commands == log.count());
  CHECK(outcome.harnessKeptUp());
  CHECK(outcome.verification.events() > 0);
  CHECK(outcome.verification.digest() != benchmarks::VerificationRecord::FNV_OFFSET);
  CHECK(std::cmp_equal(outcome.service.count, log.count()));
  const auto counts = outcome.verification.countsByName();
  CHECK(counts.at("OrderAccepted") == 3);
  CHECK(counts.at("OrderExecuted") == 1);
}

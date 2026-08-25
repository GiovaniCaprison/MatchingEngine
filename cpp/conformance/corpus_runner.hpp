// Replays a fixture against an implementation and compares what it emitted to what the fixture
// says it must emit. The runner is the publisher, and it renders each event as the engine commits
// it: reading on the engine's thread is right here and wrong in a measurement, since nothing is
// being timed. Claims advance and wrap rather than reusing one offset, so an engine that assumed
// its events always land in the same place fails here instead of on a ring buffer. Comparison is
// over words, so a fixture can align its columns, and every fixture also builds the book a
// consumer would build from the events (FR-8.1).

#pragma once

#include <cstddef>
#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "api/event_publisher.hpp"
#include "api/matching_engine.hpp"
#include "conformance/consumer_book.hpp"
#include "conformance/event_reader.hpp"
#include "conformance/fixture.hpp"

namespace io::github::giovanicaprison::matching::conformance {

class CorpusRunner final : public api::EventPublisher {
 public:
  // A fresh engine with no instrument configured and an empty book, publishing to the runner.
  using EngineFactory = std::function<std::unique_ptr<api::MatchingEngine>(api::EventPublisher&)>;

  // What one fixture did, and how it differs from what it should have done.
  class Result {
   public:
    Result(std::string name, std::vector<std::string> expected, std::vector<std::string> emitted,
           std::vector<std::pair<Command, std::vector<std::string>>> byCommand,
           std::vector<std::string> problems)
        : name_(std::move(name)),
          expected_(std::move(expected)),
          emitted_(std::move(emitted)),
          byCommand_(std::move(byCommand)),
          problems_(std::move(problems)) {}

    bool passed() const { return firstDifference() < 0 && problems_.empty(); }

    // The index of the first line that differs, or minus one when nothing does.
    int firstDifference() const;

    // The failure, and the fixture as it would read if the engine were right.
    std::string describe() const;

    const std::vector<std::string>& emitted() const { return emitted_; }
    const std::vector<std::string>& problems() const { return problems_; }

   private:
    std::string asFixture() const;

    std::string name_;
    std::vector<std::string> expected_;
    std::vector<std::string> emitted_;
    std::vector<std::pair<Command, std::vector<std::string>>> byCommand_;
    std::vector<std::string> problems_;
  };

  // Runs one fixture. A throw from the engine is a failure of the fixture, not of the harness.
  static Result run(const Fixture& fixture, const EngineFactory& factory);

  std::size_t claim(std::size_t length) override;
  char* buffer() override { return events_.data(); }
  void commit() override;

 private:
  CorpusRunner();

  static constexpr std::size_t CAPACITY = 1 << 20;

  std::vector<char> events_;
  References references_;
  ConsumerBook rebuilt_;
  EventReader reader_;

  std::vector<std::string> emitted_;
  std::vector<std::pair<Command, std::vector<std::string>>> byCommand_;

  std::size_t cursor_ = 0;
  std::size_t claimed_ = 0;
  std::size_t claimedLength_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::conformance

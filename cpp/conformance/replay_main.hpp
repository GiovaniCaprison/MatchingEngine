// The body of every rung's differential binary: read the MEFLOW01 log both languages read, replay
// it through one engine, and write every byte published for the other side to diff (NFR-5.1). Each
// rung's binary is one line naming its engine, and the log format lives in CommandLog, read once
// for the harness and for this.

#pragma once

#include <fstream>
#include <iostream>
#include <vector>

#include "api/event_publisher.hpp"
#include "conformance/command_log.hpp"

namespace io::github::giovanicaprison::matching::conformance {

// A publisher that keeps what was committed. Claims advance and wrap like the ring the harness
// uses, so an engine that assumed a fixed offset would fail here too.
class CapturingPublisher final : public api::EventPublisher {
 public:
  std::size_t claim(const std::size_t length) override {
    if (cursor_ + length > events_.size()) {
      cursor_ = 0;
    }
    claimed_ = cursor_;
    claimedLength_ = length;
    cursor_ += length;
    return claimed_;
  }

  char* buffer() override { return events_.data(); }

  void commit() override {
    captured_.insert(captured_.end(), events_.begin() + static_cast<long>(claimed_),
                     events_.begin() + static_cast<long>(claimed_ + claimedLength_));
  }

  const std::vector<char>& captured() const { return captured_; }

 private:
  std::vector<char> events_ = std::vector<char>(1 << 20);
  std::vector<char> captured_;
  std::size_t cursor_ = 0;
  std::size_t claimed_ = 0;
  std::size_t claimedLength_ = 0;
};

template <typename Engine>
int replayMain(const int count, char** arguments) {
  if (count != 3) {
    std::cerr << "usage: <command log> <events out>\n";
    return 2;
  }
  CommandLog log;
  try {
    log = CommandLog::readFrom(arguments[1]);
  } catch (const std::exception& refused) {
    std::cerr << refused.what() << "\n";
    return 2;
  }
  CapturingPublisher events;
  Engine engine(events);
  for (std::size_t command = 0; command < log.count(); command++) {
    engine.onCommand(log.buffer.data(), log.offsets[command], log.lengths[command]);
  }
  std::ofstream out(arguments[2], std::ios::binary);
  out.write(events.captured().data(), static_cast<long>(events.captured().size()));
  out.close();
  return out ? 0 : 2;
}

}  // namespace io::github::giovanicaprison::matching::conformance

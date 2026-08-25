// This rung's half of the differential mechanism (NFR-5.1): read the log both languages read,
// replay it, and write every byte the engine published, in order, for the other side to diff. The
// log format is MEFLOW01 as the Java generator writes it, because one generator produces the input
// and neither language owns it.

#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

#include "api/event_publisher.hpp"
#include "naive/naive_engine.hpp"

namespace {

namespace api = io::github::giovanicaprison::matching::api;
namespace naive = io::github::giovanicaprison::matching::naive;

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

std::int32_t readInt(std::ifstream& in) {
  unsigned char bytes[4];
  in.read(reinterpret_cast<char*>(bytes), 4);
  return static_cast<std::int32_t>(bytes[0] | bytes[1] << 8 | bytes[2] << 16 |
                                   static_cast<std::uint32_t>(bytes[3]) << 24);
}

}  // namespace

int main(const int count, char** arguments) {
  if (count != 3) {
    std::cerr << "usage: differential-naive <command log> <events out>\n";
    return 2;
  }
  std::ifstream in(arguments[1], std::ios::binary);
  if (!in) {
    std::cerr << "cannot read " << arguments[1] << "\n";
    return 2;
  }
  char magic[8];
  in.read(magic, 8);
  if (std::memcmp(magic, "MEFLOW01", 8) != 0) {
    std::cerr << arguments[1] << " is not a command log\n";
    return 2;
  }
  const std::int32_t commands = readInt(in);
  readInt(in);  // The measured-from marker, which a replay of the whole log has no use for.

  CapturingPublisher events;
  naive::NaiveEngine engine(events);
  std::vector<char> command;
  for (std::int32_t at = 0; at < commands; at++) {
    const std::int32_t length = readInt(in);
    command.resize(static_cast<std::size_t>(length));
    in.read(command.data(), length);
    if (!in) {
      std::cerr << arguments[1] << " ended " << (commands - at) << " commands early\n";
      return 2;
    }
    engine.onCommand(command.data(), 0, static_cast<std::size_t>(length));
  }

  std::ofstream out(arguments[2], std::ios::binary);
  out.write(events.captured().data(), static_cast<long>(events.captured().size()));
  out.close();
  return out ? 0 : 2;
}

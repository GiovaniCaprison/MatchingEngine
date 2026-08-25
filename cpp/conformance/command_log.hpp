// The MEFLOW01 command log, read into one buffer with an index, exactly as the Java side holds
// it: replaying touches the same memory in the same order every time, and only one generator ever
// wrote the bytes.

#pragma once

#include <cstdint>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace io::github::giovanicaprison::matching::conformance {

struct CommandLog {
  std::vector<char> buffer;
  std::vector<std::size_t> offsets;
  std::vector<std::size_t> lengths;
  std::size_t measuredFrom = 0;

  std::size_t count() const { return offsets.size(); }

  static CommandLog readFrom(const std::string& file) {
    std::ifstream in(file, std::ios::binary);
    if (!in) {
      throw std::runtime_error("cannot read the log at " + file);
    }
    char magic[8];
    in.read(magic, 8);
    if (std::memcmp(magic, "MEFLOW01", 8) != 0) {
      throw std::runtime_error(file + " is not a command log");
    }
    const std::int32_t count = readInt(in);
    CommandLog log;
    log.measuredFrom = static_cast<std::size_t>(readInt(in));
    log.offsets.reserve(static_cast<std::size_t>(count));
    log.lengths.reserve(static_cast<std::size_t>(count));
    for (std::int32_t command = 0; command < count; command++) {
      const std::int32_t length = readInt(in);
      const std::size_t at = log.buffer.size();
      log.buffer.resize(at + static_cast<std::size_t>(length));
      in.read(log.buffer.data() + at, length);
      if (!in) {
        throw std::runtime_error(file + " ended " + std::to_string(count - command) +
                                 " commands early");
      }
      log.offsets.push_back(at);
      log.lengths.push_back(static_cast<std::size_t>(length));
    }
    return log;
  }

 private:
  static std::int32_t readInt(std::ifstream& in) {
    unsigned char bytes[4];
    in.read(reinterpret_cast<char*>(bytes), 4);
    return static_cast<std::int32_t>(bytes[0] | bytes[1] << 8 | bytes[2] << 16 |
                                     static_cast<std::uint32_t>(bytes[3]) << 24);
  }
};

}  // namespace io::github::giovanicaprison::matching::conformance

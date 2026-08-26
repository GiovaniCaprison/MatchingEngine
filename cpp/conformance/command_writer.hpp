// Turns a fixture command into the bytes an engine receives. An order reference is the client
// order id the harness gave that order, so a cancel or a replace needs nothing an engine has to
// report first, and the instrument id and input sequence are the harness's rather than the
// fixture's, which is the arrangement the engine always has: input arrives already sequenced.

#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#include "conformance/fixture.hpp"
#include "conformance/references.hpp"

namespace io::github::giovanicaprison::matching::conformance {

class CommandWriter {
 public:
  explicit CommandWriter(References& references) : references_(references) {}

  char* buffer() { return buffer_.data(); }

  // Encodes one command at offset zero and returns its length in bytes.
  std::size_t write(const Command& command);

 private:
  using Options = std::unordered_map<std::string, std::string>;

  std::size_t instrument(const std::vector<std::string>& arguments);
  std::size_t session(const std::vector<std::string>& arguments);
  std::size_t newOrder(const std::vector<std::string>& arguments);
  std::size_t cancel(const std::vector<std::string>& arguments);
  std::size_t replace(const std::vector<std::string>& arguments);
  std::size_t massCancel(const std::vector<std::string>& arguments);

  std::array<char, 512> buffer_{};
  References& references_;
  std::uint64_t sequence_ = 0;
  int orders_ = 0;
};

}  // namespace io::github::giovanicaprison::matching::conformance

// The mapping between what a fixture writes and what an engine chose. A fixture names an order
// #n, counting NEW directives from one, and that number is the client order id the harness gives
// it. An event names it the way the engine does, and that id is never written down: it is rendered
// back through the id reported on acceptance, and execution ids the same way as @n for the nth
// distinct one in the stream.

#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>

namespace io::github::giovanicaprison::matching::conformance {

class References {
 public:
  void declare(const int reference, const int participant) {
    participantByReference_[reference] = participant;
  }

  void bind(const int reference, const std::uint64_t orderId) {
    referenceByOrderId_[orderId] = reference;
  }

  int participant(const int reference) const {
    const auto found = participantByReference_.find(reference);
    return found == participantByReference_.end() ? 0 : found->second;
  }

  // How an order id is written in output, falling back to the raw id so a diff stays readable.
  std::string render(const std::uint64_t orderId) const {
    const auto found = referenceByOrderId_.find(orderId);
    if (found == referenceByOrderId_.end()) {
      return "id=" + std::to_string(orderId);
    }
    return "#" + std::to_string(found->second);
  }

  std::string renderExecution(const std::uint64_t executionId) {
    const auto ordinal =
        executionOrdinals_.try_emplace(executionId, executionOrdinals_.size() + 1).first;
    return "@" + std::to_string(ordinal->second);
  }

 private:
  std::unordered_map<std::uint64_t, int> referenceByOrderId_;
  std::unordered_map<int, int> participantByReference_;
  std::unordered_map<std::uint64_t, std::size_t> executionOrdinals_;
};

}  // namespace io::github::giovanicaprison::matching::conformance

// Turns an event into the line a fixture writes for it. Every event also goes into the book a
// consumer would build, since this is the one place that decodes each event exactly once, and
// rendering and rebuilding from the same decode keeps the two from disagreeing about what an event
// said. Sequence numbers are left out of the rendering because they are a property of the stream
// rather than of the event.

#pragma once

#include <cstddef>
#include <string>

#include "conformance/consumer_book.hpp"
#include "conformance/references.hpp"

namespace io::github::giovanicaprison::matching::conformance {

class EventReader {
 public:
  EventReader(References& references, ConsumerBook& rebuilt)
      : references_(references), rebuilt_(rebuilt) {}

  std::string read(char* buffer, std::size_t offset, std::size_t length);

 private:
  References& references_;
  ConsumerBook& rebuilt_;
};

}  // namespace io::github::giovanicaprison::matching::conformance

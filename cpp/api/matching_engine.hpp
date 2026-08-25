// A matching engine: an ordered stream of commands in, an ordered stream of events out. The engine
// is a function of its input (P-1): the same commands in the same order produce the same events,
// byte for byte, which is what makes two implementations comparable and a replay exact. One
// instrument per instance, one thread per instance (P-2); the first command is an instrument
// definition and its fields are trusted (P-14).

#pragma once

#include <cstddef>

namespace io::github::giovanicaprison::matching::api {

class MatchingEngine {
 public:
  virtual ~MatchingEngine() = default;

  // Applies one command and publishes whatever it caused. The slice holds exactly one framed
  // message (P-14); a malformed message is a programming error in whatever framed it.
  virtual void onCommand(char* buffer, std::size_t offset, std::size_t length) = 0;
};

}  // namespace io::github::giovanicaprison::matching::api

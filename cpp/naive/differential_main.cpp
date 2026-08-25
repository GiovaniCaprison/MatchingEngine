// This rung's half of the differential mechanism (NFR-5.1): one line naming the engine, with the
// log format and the capture shared by every rung's binary.

#include "conformance/replay_main.hpp"
#include "naive/naive_engine.hpp"

int main(const int count, char** arguments) {
  namespace matching = io::github::giovanicaprison::matching;
  return matching::conformance::replayMain<matching::naive::NaiveEngine>(count, arguments);
}

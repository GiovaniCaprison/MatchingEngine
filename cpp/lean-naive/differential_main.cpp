// The lean arm's half of the differential (NFR-5.1): one line naming the engine, with the log
// format and the capture shared by every rung's binary.

#include "conformance/replay_main.hpp"
#include "lean-naive/lean_engine.hpp"

int main(const int count, char** arguments) {
  namespace matching = io::github::giovanicaprison::matching;
  return matching::conformance::replayMain<matching::lean::naive::LeanEngine>(count, arguments);
}

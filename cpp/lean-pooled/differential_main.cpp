// Rung one's lean twin, its half of the differential (NFR-5.1).

#include "conformance/replay_main.hpp"
#include "lean-pooled/lean_engine.hpp"

int main(const int count, char** arguments) {
  namespace matching = io::github::giovanicaprison::matching;
  return matching::conformance::replayMain<matching::lean::pooled::LeanEngine>(count, arguments);
}

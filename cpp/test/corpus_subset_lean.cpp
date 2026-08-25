// The corpus, restricted to what the lean engine is, replayed in this language. The filter is the
// Java subset test's filter word for word: a fixture is inside the shared remit when no NEW line
// carries a qualifier, no session enters a call phase, and the instrument allocates price-time.

#include <catch2/catch_test_macros.hpp>
#include <memory>
#include <string>

#include "conformance/corpus.hpp"
#include "conformance/corpus_runner.hpp"
#include "lean/lean_engine.hpp"

using namespace io::github::giovanicaprison::matching;

namespace {

bool qualified(const conformance::Command& command) {
  for (const std::string& argument : command.arguments) {
    if (argument.starts_with("min=") || argument.starts_with("display=") ||
        argument.starts_with("trigger=") || argument.starts_with("smp=") ||
        argument == "POST_ONLY") {
      return true;
    }
  }
  return command.arguments[2] == "FOK";
}

bool sharedRemit(const conformance::Fixture& fixture) {
  for (const conformance::Command& command : fixture.commands()) {
    if (command.directive == conformance::Directive::NEW && qualified(command)) {
      return false;
    }
    if (command.directive == conformance::Directive::SESSION &&
        command.arguments.front().find("AUCTION") != std::string::npos) {
      return false;
    }
    if (command.directive == conformance::Directive::INSTRUMENT) {
      for (const std::string& argument : command.arguments) {
        if (argument == "alloc=PRO_RATA") {
          return false;
        }
      }
    }
  }
  return true;
}

}  // namespace

TEST_CASE("the corpus subset inside the shared remit passes against the lean engine") {
  int inside = 0;
  for (const conformance::Fixture& fixture : conformance::corpusFixtures()) {
    if (!sharedRemit(fixture)) {
      continue;
    }
    inside++;
    const conformance::CorpusRunner::Result result = conformance::CorpusRunner::run(
        fixture,
        [](api::EventPublisher& events) { return std::make_unique<lean::LeanEngine>(events); });
    INFO(result.describe());
    CHECK(result.passed());
  }
  CHECK(inside >= 25);
}

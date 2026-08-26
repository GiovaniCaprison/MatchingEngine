// The whole specification against rung three in this language. The same 77 files every engine
// passes, because a rung is the same engine at a different layout and the corpus is what makes
// that claim checkable across four implementations and two languages (NFR-5.2, NFR-5.3).

#include <catch2/catch_test_macros.hpp>
#include <memory>

#include "conformance/corpus.hpp"
#include "conformance/corpus_runner.hpp"
#include "flyweight/flyweight_engine.hpp"

using namespace io::github::giovanicaprison::matching;

TEST_CASE("every fixture in the corpus passes against rung three in this language") {
  const std::vector<conformance::Fixture> fixtures = conformance::corpusFixtures();
  REQUIRE(fixtures.size() >= 77);
  for (const conformance::Fixture& fixture : fixtures) {
    const conformance::CorpusRunner::Result result =
        conformance::CorpusRunner::run(fixture, [](api::EventPublisher& events) {
          return std::make_unique<flyweight::FlyweightEngine>(events);
        });
    INFO(result.describe());
    CHECK(result.passed());
  }
}

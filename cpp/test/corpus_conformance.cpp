// The whole specification, replayed against this language's rung zero. The fixtures are the
// contract: the same 77 files the Java engine passes, read from the same directory, so a
// disagreement here is a disagreement between two engines about what a venue does and not between
// two readings of a document.

#include <catch2/catch_test_macros.hpp>
#include <memory>

#include "conformance/corpus.hpp"
#include "conformance/corpus_runner.hpp"
#include "naive/naive_engine.hpp"

using namespace io::github::giovanicaprison::matching;

TEST_CASE("NFR-5.3 every fixture in the corpus passes in this language") {
  const std::vector<conformance::Fixture> fixtures = conformance::corpusFixtures();
  REQUIRE(fixtures.size() >= 77);
  for (const conformance::Fixture& fixture : fixtures) {
    const conformance::CorpusRunner::Result result = conformance::CorpusRunner::run(
        fixture,
        [](api::EventPublisher& events) { return std::make_unique<naive::NaiveEngine>(events); });
    INFO(result.describe());
    CHECK(result.passed());
  }
}

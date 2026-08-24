package io.github.giovanicaprison.matching.naive.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.conformance.Corpus;
import io.github.giovanicaprison.matching.conformance.CorpusRunner;
import io.github.giovanicaprison.matching.conformance.Fixture;
import io.github.giovanicaprison.matching.naive.NaiveEngineFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every fixture in the corpus, replayed against this engine.
 *
 * <p>One line, because the suite is not this rung's. The rules state the remit and the scenarios
 * state interactions, and both belong to the specification rather than to an implementation, so
 * every rung in either language runs the same directory. Four copies of the same rules would be
 * four copies that drift, and "the same suite" would stop being checkable.
 *
 * <p>The fixtures were written from the requirements rather than blessed from any engine's output,
 * so a failure here is either a defect or a misreading of the specification, and which one has to
 * be decided by reading rather than by pasting.
 */
class CorpusConformanceTest {

  @DisplayName("NFR-5.2 this implementation passes the corpus every implementation passes")
  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void a_fixture_passes(final Fixture fixture) {
    final CorpusRunner.Result result = CorpusRunner.run(fixture, new NaiveEngineFactory());

    assertThat(result.passed()).as(result.describe()).isTrue();
  }

  static Stream<Fixture> fixtures() {
    return Corpus.fixtures().stream();
  }
}

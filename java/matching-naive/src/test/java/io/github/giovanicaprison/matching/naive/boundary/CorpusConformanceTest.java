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
 * The fixtures, replayed against this engine.
 *
 * <p>They were written from the requirements rather than blessed from this engine's output, so a
 * failure here is either a defect or a misreading of the specification, and which one it is has to
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

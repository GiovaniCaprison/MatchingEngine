package io.github.giovanicaprison.matching.pooled.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.conformance.Corpus;
import io.github.giovanicaprison.matching.conformance.CorpusRunner;
import io.github.giovanicaprison.matching.conformance.Fixture;
import io.github.giovanicaprison.matching.pooled.PooledEngineFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The whole specification against rung two, through the public interface only. The same suite the
 * naive engine passes, because a rung is the same engine at a different layout and the corpus is
 * what makes that claim checkable (NFR-5.2).
 */
class CorpusConformanceTest {

  @DisplayName("{0}")
  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void the_fixture_holds(final Fixture fixture) {
    final CorpusRunner.Result result = CorpusRunner.run(fixture, new PooledEngineFactory());

    assertThat(result.passed()).as(result.describe()).isTrue();
  }

  static Stream<Fixture> fixtures() {
    return Corpus.fixtures().stream();
  }
}

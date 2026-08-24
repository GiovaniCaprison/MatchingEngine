package io.github.giovanicaprison.matching.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fixtures on disk are syntax checked here and behaviour checked by whichever implementation
 * replays them. A fixture that no engine can run yet is still a fixture that has to parse.
 */
class CorpusTest {

  @Test
  @DisplayName("every fixture in the corpus parses")
  void the_corpus_parses() {
    assertThat(Corpus.fixtures())
        .as("fixtures found in " + Corpus.directory())
        .isNotEmpty()
        .allSatisfy(fixture -> assertThat(fixture.commands()).isNotEmpty());
  }

  @Test
  @DisplayName("every fixture expects some output")
  void every_fixture_asserts_something() {
    // A fixture with no expected lines passes against an engine that does nothing at all.
    assertThat(Corpus.fixtures())
        .allSatisfy(fixture -> assertThat(fixture.expectedOutput()).isNotEmpty());
  }
}

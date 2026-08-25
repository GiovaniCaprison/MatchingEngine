package io.github.giovanicaprison.matching.lean.indexed.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.conformance.Corpus;
import io.github.giovanicaprison.matching.conformance.CorpusRunner;
import io.github.giovanicaprison.matching.conformance.Directive;
import io.github.giovanicaprison.matching.conformance.Fixture;
import io.github.giovanicaprison.matching.lean.indexed.LeanEngineFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The corpus, restricted to what this engine is: limit and market orders, price-time, no
 * qualifiers, no auctions.
 *
 * <p>A fixture that exercises a feature this engine does not have proves nothing about it either
 * way, so the suite is the corpus filtered by what its commands actually use. On that subset the
 * expected output is the same blessed output the full engine produces, which is the point: the two
 * engines answer the shared remit identically, and the difference between them is existence alone
 * (P-16).
 */
class CorpusSubsetTest {

  @DisplayName("{0}")
  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void the_shared_remit_reads_the_same(final Fixture fixture) {
    final CorpusRunner.Result result = CorpusRunner.run(fixture, new LeanEngineFactory());

    assertThat(result.passed()).as(result.describe()).isTrue();
  }

  @Test
  @DisplayName("the subset is a real suite, so the filter cannot rot it away")
  void the_subset_stays_substantial() {
    assertThat(fixtures().count())
        .as("fixtures inside the shared remit; a fall here means the filter drifted")
        .isGreaterThanOrEqualTo(25);
  }

  static Stream<Fixture> fixtures() {
    return Corpus.fixtures().stream().filter(CorpusSubsetTest::sharedRemit);
  }

  /** Whether a fixture stays inside what this engine is, judged by what its commands use. */
  private static boolean sharedRemit(final Fixture fixture) {
    for (final Fixture.Command command : fixture.commands()) {
      if (command.directive() == Directive.NEW && qualified(command)) {
        return false;
      }
      if (command.directive() == Directive.SESSION
          && command.arguments().getFirst().contains("AUCTION")) {
        return false;
      }
      if (command.directive() == Directive.INSTRUMENT
          && command.arguments().contains("alloc=PRO_RATA")) {
        return false;
      }
    }
    return true;
  }

  private static boolean qualified(final Fixture.Command command) {
    for (final String argument : command.arguments()) {
      if (argument.startsWith("min=")
          || argument.startsWith("display=")
          || argument.startsWith("trigger=")
          || argument.startsWith("smp=")
          || argument.equals("POST_ONLY")) {
        return true;
      }
    }
    return "FOK".equals(command.arguments().get(2));
  }
}

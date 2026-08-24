package io.github.giovanicaprison.matching.naive.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.conformance.CorpusRunner;
import io.github.giovanicaprison.matching.conformance.FixtureParser;
import io.github.giovanicaprison.matching.naive.NaiveEngineFactory;

/**
 * Drives the engine for a unit test, in the format the corpus is written in.
 *
 * <p>One notation for both, so a rule stated in a test reads the same as a rule stated in a
 * fixture, and neither needs a second renderer to be believed. The difference is what they are for:
 * a test here states one rule with its result written out as a literal, and a fixture states an
 * interaction.
 *
 * <p>Nothing reaches inside the implementation. These are the tests a rewrite has to keep passing,
 * so they live outside its package and the compiler keeps them there.
 */
final class Engine {

  /** Tick five, lot one, and a band wide enough that placement is not the subject. */
  static final String INSTRUMENT =
      "INSTRUMENT tick=5 lot=1 scale=4 min=1 max=1000000 band=500 open=100000 alloc=PRICE_TIME";

  static final String PRO_RATA =
      "INSTRUMENT tick=5 lot=1 scale=4 min=1 max=1000000 band=500 open=100000 alloc=PRO_RATA";

  private Engine() {}

  /** The whole fixture, instrument line and all. */
  static void assertEvents(final String fixture) {
    final CorpusRunner.Result result =
        CorpusRunner.run(FixtureParser.parse("inline", fixture), new NaiveEngineFactory());

    assertThat(result.passed()).as(result.describe()).isTrue();
  }

  /** The standard instrument, already in continuous trading, which is most rules' setting. */
  static void assertContinuous(final String fixture) {
    assertEvents(INSTRUMENT + "\nSESSION CONTINUOUS\nSTATE CONTINUOUS\n" + fixture);
  }

  /** The standard instrument in continuous trading, with a pro-rata price level. */
  static void assertProRata(final String fixture) {
    assertEvents(PRO_RATA + "\nSESSION CONTINUOUS\nSTATE CONTINUOUS\n" + fixture);
  }

  /** No state command at all, which is where an engine starts (FR-7.9). */
  static void assertBeforeOpen(final String fixture) {
    assertEvents(INSTRUMENT + "\n" + fixture);
  }
}

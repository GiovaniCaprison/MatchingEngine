package io.github.giovanicaprison.matching.lean.indexed.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.conformance.DifferentialReplay;
import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import io.github.giovanicaprison.matching.indexed.IndexedEngineFactory;
import io.github.giovanicaprison.matching.lean.indexed.LeanEngineFactory;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The honesty condition on the feature cost comparison.
 *
 * <p>Comparing the full engine against this one only measures the cost of features existing if the
 * two do exactly the same work on flow that uses neither. An engine that answered differently would
 * be measuring a behaviour difference and calling it a feature cost, so the two outputs are held
 * byte identical over generated flow at the limit-and-market composition, which is the very flow
 * the comparison runs on.
 */
class SharedRemitTest {

  @DisplayName("the full indexed engine and its lean twin agree on the shared remit, to the byte")
  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {5, 20260825})
  void the_two_arms_agree(final long seed) {
    final CommandLog log = FlowGenerator.generate(leanFlow(seed));

    final byte[] full = DifferentialReplay.replay(log, new IndexedEngineFactory());
    final byte[] lean = DifferentialReplay.replay(log, new LeanEngineFactory());

    final int difference = Arrays.mismatch(full, lean);
    assertThat(difference)
        .as(
            "the streams part company at byte %d of %d (indexed) and %d (lean), so the comparison"
                + " between these two engines would measure a behaviour difference",
            difference, full.length, lean.length)
        .isEqualTo(-1);
  }

  private static FlowParameters leanFlow(final long seed) {
    return new FlowParameters(
        seed,
        40_000,
        5_000,
        FlowParameters.Instrument.standard(),
        FlowParameters.Composition.limitAndMarketOnly(),
        FlowParameters.Placement.standard(),
        0);
  }
}

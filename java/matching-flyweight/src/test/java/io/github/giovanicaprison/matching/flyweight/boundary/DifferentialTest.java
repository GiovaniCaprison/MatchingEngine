package io.github.giovanicaprison.matching.flyweight.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.conformance.DifferentialReplay;
import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import io.github.giovanicaprison.matching.flyweight.FlyweightEngineFactory;
import io.github.giovanicaprison.matching.naive.NaiveEngineFactory;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rung against the arbiter, byte for byte (NFR-5.1).
 *
 * <p>The naive engine is the one written to be obviously right, so it judges this one. Two books
 * this far apart in representation producing identical bytes over flow that uses everything is what
 * licenses calling their measured difference a cost of representation, and it is the mechanism most
 * likely to catch the failures this layout invents: a stale bit in the occupancy summary, a tick
 * folded to the wrong rank, or a slot reissued with a field left over from its previous life, since
 * either book alone stays internally consistent through all of them.
 */
class DifferentialTest {

  @DisplayName("NFR-5.1 rung three and rung zero emit byte identical output from identical input")
  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {11, 20260826})
  void the_rungs_agree(final long seed) {
    final CommandLog log = FlowGenerator.generate(FlowParameters.withAuctions(seed, 40_000, 2_000));

    final byte[] arbiter = DifferentialReplay.replay(log, new NaiveEngineFactory());
    final byte[] flyweight = DifferentialReplay.replay(log, new FlyweightEngineFactory());

    final int difference = Arrays.mismatch(arbiter, flyweight);
    assertThat(difference)
        .as(
            "the streams part company at byte %d of %d (naive) and %d (flyweight), so the ladder"
                + " changed behaviour and not only representation",
            difference, arbiter.length, flyweight.length)
        .isEqualTo(-1);
  }
}

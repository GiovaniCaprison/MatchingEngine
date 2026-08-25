package io.github.giovanicaprison.matching.pooled.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.conformance.DifferentialReplay;
import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import io.github.giovanicaprison.matching.naive.NaiveEngineFactory;
import io.github.giovanicaprison.matching.pooled.PooledEngineFactory;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rung against the arbiter, byte for byte (NFR-5.1).
 *
 * <p>The naive engine is the one written to be obviously right, so it judges this one. Two
 * different books producing identical bytes over flow that uses everything is what licenses calling
 * their measured difference a cost of representation rather than a difference of behaviour, and it
 * is the mechanism most likely to catch a pooled object reused with a field left over from its
 * previous life, since either book alone is internally consistent.
 */
class DifferentialTest {

  @DisplayName("NFR-5.1 rung two and rung zero emit byte identical output from identical input")
  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {11, 20260826})
  void the_rungs_agree(final long seed) {
    final CommandLog log = FlowGenerator.generate(FlowParameters.withAuctions(seed, 40_000, 2_000));

    final byte[] arbiter = DifferentialReplay.replay(log, new NaiveEngineFactory());
    final byte[] indexed = DifferentialReplay.replay(log, new PooledEngineFactory());

    final int difference = Arrays.mismatch(arbiter, indexed);
    assertThat(difference)
        .as(
            "the streams part company at byte %d of %d (naive) and %d (indexed), so the indexed"
                + " book changed behaviour and not only representation",
            difference, arbiter.length, indexed.length)
        .isEqualTo(-1);
  }
}

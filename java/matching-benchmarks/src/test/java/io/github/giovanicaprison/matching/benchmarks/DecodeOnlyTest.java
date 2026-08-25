package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decode arm has to actually read, and has to publish nothing, or subtracting it from an
 * engine's number attributes the wrong cost to matching (NFR-4.6).
 */
class DecodeOnlyTest {

  @Test
  @DisplayName("the decode arm reads every command and says nothing")
  void decode_reads_everything_and_publishes_nothing() {
    final CommandLog log = FlowGenerator.generate(FlowParameters.standard(3, 5_000));
    final DecodeOnlyEngine engine = new DecodeOnlyEngine();

    for (int command = 0; command < log.count(); command++) {
      engine.onCommand(log.buffer(), log.offset(command), log.length(command));
    }

    assertThat(engine.consumed())
        .as("a sum over every field of five thousand commands cannot land on zero by accident")
        .isNotZero();
  }

  @Test
  @DisplayName("the decode arm runs under the same harness as any engine")
  void the_harness_carries_it() {
    final CommandLog log = FlowGenerator.generate(FlowParameters.standard(3, 5_000));

    final Measurement.Outcome outcome =
        Measurement.run(
            log,
            new DecodeOnlyEngine.Factory(),
            MeasurementParameters.at(200_000, MeasurementParameters.Cores.anywhere()));

    assertThat(outcome.commands()).isEqualTo(log.count());
    assertThat(outcome.verification().events()).as("decode publishes nothing").isZero();
    assertThat(outcome.harnessKeptUp()).isTrue();
  }
}

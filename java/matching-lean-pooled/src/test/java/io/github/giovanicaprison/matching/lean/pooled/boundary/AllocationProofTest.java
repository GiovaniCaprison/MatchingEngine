package io.github.giovanicaprison.matching.lean.pooled.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lean twin's zero allocation claim, proved the way the full rung proves it (NFR-4.3).
 *
 * <p>The comparison between a rung and its twin is only about the feature set if both arms carry
 * the rung's mechanism, so the twin is held to the same standard: it finishes a long session under
 * Epsilon inside a budget, and the lean-naive control arm, which differs from it only by the
 * mechanism, dies there.
 */
class AllocationProofTest {

  private static final int COMMANDS = 800_000;
  private static final String BUDGET = "-Xmx80m";

  @TempDir private Path exchanged;

  @DisplayName("NFR-4.3 the lean twin finishes a session under a collector that never collects")
  @Test
  void the_steady_state_allocates_nothing() throws Exception {
    assertThat(probe("lean-pooled"))
        .as("the lean twin outlived the budget only if its steady state allocates nothing")
        .isZero();
  }

  @DisplayName("NFR-4.3 the lean-naive control arm dies under the same budget")
  @Test
  void the_control_arm_dies() throws Exception {
    assertThat(probe("lean-naive"))
        .as("an engine that allocates per command has to exhaust a heap that is never collected")
        .isNotZero();
  }

  private int probe(final String engine) throws Exception {
    final CommandLog log =
        FlowGenerator.generate(
            new FlowParameters(
                31,
                COMMANDS,
                5_000,
                FlowParameters.Instrument.standard(),
                FlowParameters.Composition.limitAndMarketOnly(),
                FlowParameters.Placement.standard(),
                0));
    final Path logFile = exchanged.resolve("commands.log");
    log.writeTo(logFile);
    final Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseEpsilonGC",
                BUDGET,
                "--add-exports",
                "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                "io.github.giovanicaprison.matching.lean.pooled.EpsilonProbe",
                logFile.toString(),
                engine)
            .redirectErrorStream(true)
            .start();
    process.getInputStream().readAllBytes();
    return process.waitFor();
  }
}

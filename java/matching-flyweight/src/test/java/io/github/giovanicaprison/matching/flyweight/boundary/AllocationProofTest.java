package io.github.giovanicaprison.matching.flyweight.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The zero allocation claim, proved rather than trusted (NFR-4.3).
 *
 * <p>Epsilon allocates but never collects, so a heap sized to the setup and little more is a
 * per-run allocation budget. The flyweight engine's setup is the largest on the ladder, since the
 * price ladder holds a slot for every tick the instrument can name, so the probe indexes the log
 * file's bytes in place to keep the harness off the engine's bill. The control arm is the same
 * probe holding the naive engine, which allocates per command and has to die there; without it a
 * passing run would also be consistent with a budget too generous to catch anything.
 */
class AllocationProofTest {

  private static final int COMMANDS = 200_000;
  private static final String BUDGET = "-Xmx64m";

  @TempDir private Path exchanged;

  @DisplayName("NFR-4.3 rung three finishes a session under a collector that never collects")
  @Test
  void the_steady_state_allocates_nothing() throws Exception {
    assertThat(probe("flyweight"))
        .as("the flyweight engine outlived the budget only if its steady state allocates nothing")
        .isZero();
  }

  @DisplayName("NFR-4.3 the control arm dies under the same budget, so the budget means something")
  @Test
  void the_control_arm_dies() throws Exception {
    assertThat(probe("naive"))
        .as("an engine that allocates per command has to exhaust a heap that is never collected")
        .isNotZero();
  }

  private int probe(final String engine) throws Exception {
    final CommandLog log = FlowGenerator.generate(FlowParameters.withAuctions(31, COMMANDS, 2_000));
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
                "io.github.giovanicaprison.matching.flyweight.EpsilonProbe",
                logFile.toString(),
                engine)
            .redirectErrorStream(true)
            .start();
    process.getInputStream().readAllBytes();
    return process.waitFor();
  }
}

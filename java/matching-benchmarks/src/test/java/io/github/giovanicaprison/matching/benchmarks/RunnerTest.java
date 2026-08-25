package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole thing, end to end, against an implementation this module does not compile against.
 *
 * <p>That is the part worth testing here: the engine is named on the command line and built by
 * reflection, so a broken class name or a factory without a no-argument constructor fails at the
 * one place a run can still be abandoned cheaply.
 */
class RunnerTest {

  private static final String NAIVE = "io.github.giovanicaprison.matching.naive.NaiveEngineFactory";

  @TempDir private Path results;

  @Test
  @DisplayName("a run leaves a directory with everything in it")
  void a_run_writes_its_artifacts() throws Exception {
    Runner.main(
        new String[] {
          "--implementation", NAIVE,
          "--label", "naive",
          "--rate", "100000",
          "--commands", "2000",
          "--warmup", "0",
          "--resting", "100",
          "--seed", "3",
          "--results", results.toString()
        });

    final Path run = only(results);
    assertThat(run.resolve("manifest.json")).isNotEmptyFile();
    assertThat(run.resolve("measurement.json")).isNotEmptyFile();
    assertThat(run.resolve("verification.json")).isNotEmptyFile();
    assertThat(run.resolve("latency.hdr")).isNotEmptyFile();
    assertThat(run.resolve("timings.bin")).isNotEmptyFile();
    assertThat(run.resolve("manifest.json"))
        .content()
        .contains("\"implementation\": \"" + NAIVE + "\"");
    assertThat(run.resolve("verification.json")).content().contains("OrderAccepted");
  }

  @Test
  @DisplayName("a run replays a log from a file when asked, which is how a real session runs")
  void a_run_replays_a_file() throws Exception {
    final Path logFile = results.resolve("session.log");
    io.github.giovanicaprison.matching.flow.FlowGenerator.generate(
            io.github.giovanicaprison.matching.flow.FlowParameters.standard(9, 2_000))
        .writeTo(logFile);

    Runner.main(
        new String[] {
          "--implementation",
          NAIVE,
          "--label",
          "replayed",
          "--log",
          logFile.toString(),
          "--rate",
          "100000",
          "--warmup",
          "0",
          "--results",
          results.resolve("runs").toString()
        });

    final Path run = only(results.resolve("runs"));
    assertThat(run.resolve("manifest.json")).content().contains("\"source\": \"" + logFile);
    assertThat(run.resolve("verification.json")).content().contains("OrderAccepted");
  }

  @Test
  @DisplayName("an implementation that cannot be constructed stops the run before it starts")
  void an_unknown_implementation_is_refused() {
    assertThat(results).isEmptyDirectory();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    Runner.main(
                        new String[] {
                          "--implementation",
                          "io.github.nowhere.NoSuchFactory",
                          "--results",
                          results.toString()
                        })))
        .isInstanceOf(ClassNotFoundException.class);
  }

  private static Path only(final Path directory) throws IOException {
    try (Stream<Path> entries = Files.list(directory)) {
      final List<Path> found = entries.toList();
      assertThat(found).hasSize(1);
      return found.getFirst();
    }
  }
}

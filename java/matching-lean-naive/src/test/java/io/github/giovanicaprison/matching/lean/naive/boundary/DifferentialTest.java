package io.github.giovanicaprison.matching.lean.naive.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.giovanicaprison.matching.conformance.DifferentialReplay;
import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import io.github.giovanicaprison.matching.lean.naive.LeanEngineFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The lean arm's cross-language differential: the Java and C++ lean engines fed identical bytes,
 * outputs diffed. With the naive pair held the same way (NFR-5.1), all four engines answer the
 * shared remit identically, which is what lets a feature cost measured in either language be
 * attributed to the features rather than to a behaviour difference.
 */
class DifferentialTest {

  @TempDir private Path exchanged;

  @DisplayName("the two lean engines emit byte identical output from identical input")
  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {5, 20260825})
  void the_two_lean_engines_agree(final long seed) throws Exception {
    final Path binary = binary();
    assumeTrue(Files.isExecutable(binary), "no C++ lean engine is built, nothing to diff");

    final CommandLog log =
        FlowGenerator.generate(
            new FlowParameters(
                seed,
                40_000,
                5_000,
                FlowParameters.Instrument.standard(),
                FlowParameters.Composition.limitAndMarketOnly(),
                FlowParameters.Placement.standard(),
                0));
    final Path logFile = exchanged.resolve("commands.log");
    final Path eventsFile = exchanged.resolve("events.bin");
    log.writeTo(logFile);

    final Process process =
        new ProcessBuilder(binary.toString(), logFile.toString(), eventsFile.toString())
            .redirectErrorStream(true)
            .start();
    final String said = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor()).as("the C++ replay has to finish cleanly: %s", said).isZero();

    final byte[] ours = DifferentialReplay.replay(log, new LeanEngineFactory());
    final byte[] theirs = Files.readAllBytes(eventsFile);

    final int difference = Arrays.mismatch(ours, theirs);
    assertThat(difference)
        .as(
            "the streams part company at byte %d of %d (java) and %d (c++)",
            difference, ours.length, theirs.length)
        .isEqualTo(-1);
  }

  private static Path binary() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      final Path binary = candidate.resolve("cpp/build/lean-naive/differential-lean-naive");
      if (Files.exists(binary)) {
        return binary;
      }
      candidate = candidate.getParent();
    }
    return Path.of("cpp/build/lean-naive/differential-lean-naive");
  }
}

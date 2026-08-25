package io.github.giovanicaprison.matching.flyweight.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.giovanicaprison.matching.conformance.DifferentialReplay;
import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import io.github.giovanicaprison.matching.flyweight.FlyweightEngineFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rung's cross-language differential: the Java and C++ flyweight engines fed identical bytes,
 * outputs diffed. The C++ twin does not exist yet, so this abstains until its binary appears at the
 * conventional path and arms itself without edits when it does (NFR-5.1).
 */
class TwinDifferentialTest {

  @TempDir private Path exchanged;

  @DisplayName("the two flyweight engines emit byte identical output from identical input")
  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {11, 20260826})
  void the_twins_agree(final long seed) throws Exception {
    final Path binary = binary();
    assumeTrue(Files.isExecutable(binary), "no C++ flyweight engine is built, nothing to diff");

    final CommandLog log = FlowGenerator.generate(FlowParameters.withAuctions(seed, 40_000, 2_000));
    final Path logFile = exchanged.resolve("commands.log");
    final Path eventsFile = exchanged.resolve("events.bin");
    log.writeTo(logFile);

    final Process process =
        new ProcessBuilder(binary.toString(), logFile.toString(), eventsFile.toString())
            .redirectErrorStream(true)
            .start();
    final String said = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor()).as("the C++ replay has to finish cleanly: %s", said).isZero();

    final byte[] ours = DifferentialReplay.replay(log, new FlyweightEngineFactory());
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
      final Path binary = candidate.resolve("cpp/build/flyweight/differential-flyweight");
      if (Files.exists(binary)) {
        return binary;
      }
      candidate = candidate.getParent();
    }
    return Path.of("cpp/build/flyweight/differential-flyweight");
  }
}

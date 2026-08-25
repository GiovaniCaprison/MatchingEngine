package io.github.giovanicaprison.matching.naive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.giovanicaprison.matching.conformance.DifferentialReplay;
import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Two implementations fed identical generated input, output diffed.
 *
 * <p>A disagreement means one is wrong, and this engine is the one written to be obviously right,
 * so it is the arbiter. The comparison is over the encoded bytes rather than rendered words,
 * because byte identical output is the claim (NFR-5.1) and because an allocation error is
 * internally consistent on either side: only the other engine's reading of the same input disagrees
 * with it.
 *
 * <p>The C++ side runs as the binary its build produces, fed the same log through the file format
 * both languages read. A tree whose C++ side is not built has nothing to differ with, and the test
 * abstains rather than passing vacuously.
 */
class DifferentialTest {

  @TempDir private Path exchanged;

  @DisplayName("NFR-5.1 the two engines emit byte identical output from identical input")
  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {7, 20260825})
  void the_two_engines_agree(final long seed) throws Exception {
    final Path binary = binary();
    assumeTrue(Files.isExecutable(binary), "no C++ engine is built, so there is nothing to diff");

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

    final byte[] ours = DifferentialReplay.replay(log, new NaiveEngineFactory());
    final byte[] theirs = Files.readAllBytes(eventsFile);

    final int difference = Arrays.mismatch(ours, theirs);
    assertThat(difference)
        .as(
            "the streams part company at byte %d of %d (java) and %d (c++). One of these engines"
                + " is wrong, and the corpus says which words disagree; this says the input to"
                + " chase it with",
            difference, ours.length, theirs.length)
        .isEqualTo(-1);
  }

  /** The rung's replay binary, found the way the corpus is found: by walking up. */
  private static Path binary() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      final Path binary = candidate.resolve("cpp/build/naive/differential-naive");
      if (Files.exists(binary)) {
        return binary;
      }
      candidate = candidate.getParent();
    }
    return Path.of("cpp/build/naive/differential-naive");
  }
}

package io.github.giovanicaprison.matching.lean.flyweight.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The other language's half of the lean twin's allocation proof (NFR-4.3): the C++ probe's
 * allocator counts every request made while the session replays, and one request is a failure. It
 * abstains until the C++ twin exists and arms itself without edits when it does.
 */
class TwinAllocationProofTest {

  @TempDir private Path exchanged;

  @DisplayName("NFR-4.3 the C++ lean engine never asks the allocator once the session is running")
  @Test
  void the_twin_allocates_nothing() throws Exception {
    final Path binary = binary();
    assumeTrue(Files.isExecutable(binary), "no C++ allocation probe is built, nothing to prove");

    final CommandLog log =
        FlowGenerator.generate(
            new FlowParameters(
                31,
                200_000,
                5_000,
                FlowParameters.Instrument.standard(),
                FlowParameters.Composition.limitAndMarketOnly(),
                FlowParameters.Placement.standard(),
                0));
    final Path logFile = exchanged.resolve("commands.log");
    log.writeTo(logFile);

    final Process process =
        new ProcessBuilder(binary.toString(), logFile.toString()).redirectErrorStream(true).start();
    final String said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor())
        .as("the probe's allocator was asked during the session: %s", said)
        .isZero();
  }

  private static Path binary() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      final Path binary =
          candidate.resolve("cpp/build/lean-flyweight/allocation-probe-lean-flyweight");
      if (Files.exists(binary)) {
        return binary;
      }
      candidate = candidate.getParent();
    }
    return Path.of("cpp/build/lean-flyweight/allocation-probe-lean-flyweight");
  }
}

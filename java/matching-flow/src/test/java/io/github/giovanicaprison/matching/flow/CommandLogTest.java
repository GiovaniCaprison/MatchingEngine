package io.github.giovanicaprison.matching.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A log on disk is how a Java run and a C++ run are fed the same bytes, so the round trip has to
 * carry everything the driver reads, including what it recomputes rather than stores.
 */
class CommandLogTest {

  @TempDir private Path directory;

  @Test
  @DisplayName("a log survives a round trip through a file")
  void a_log_round_trips() {
    final CommandLog written = FlowGenerator.generate(FlowParameters.standard(7, 2_000));
    final Path file = directory.resolve("flow.log");

    written.writeTo(file);
    final CommandLog read = CommandLog.readFrom(file);

    assertThat(read.count()).isEqualTo(written.count());
    assertThat(read.measuredFrom()).isEqualTo(written.measuredFrom());
    for (int command = 0; command < written.count(); command++) {
      assertThat(read.length(command)).isEqualTo(written.length(command));
      assertThat(read.templateId(command)).isEqualTo(written.templateId(command));
      assertThat(read.targetOrdinal(command)).isEqualTo(written.targetOrdinal(command));
      assertThat(message(read, command)).isEqualTo(message(written, command));
    }
  }

  @Test
  @DisplayName("a patch offset read back from a file points at the same field")
  void patch_offsets_survive_the_round_trip() {
    final CommandLog written = FlowGenerator.generate(FlowParameters.standard(7, 2_000));
    final Path file = directory.resolve("flow.log");
    written.writeTo(file);
    final CommandLog read = CommandLog.readFrom(file);

    int checked = 0;
    for (int command = 0; command < read.count(); command++) {
      if (read.patchOffset(command) < 0) {
        continue;
      }
      checked++;
      assertThat(read.buffer().getLong(read.patchOffset(command)))
          .isEqualTo(read.targetOrdinal(command));
    }
    assertThat(checked).isPositive();
  }

  @Test
  @DisplayName("a file that is not a log is refused")
  void something_else_is_refused() throws IOException {
    final Path file = directory.resolve("not-a-log");
    Files.write(file, "MEFLOW99 and then some".getBytes());

    assertThatIllegalArgumentException()
        .isThrownBy(() -> CommandLog.readFrom(file))
        .withMessageContaining("is not a command log");
  }

  private static byte[] message(final CommandLog log, final int command) {
    final byte[] bytes = new byte[log.length(command)];
    log.buffer().getBytes(log.offset(command), bytes, 0, bytes.length);
    return bytes;
  }
}

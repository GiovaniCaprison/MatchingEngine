package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The samples read a machine out of a directory, like the environment probes they sit beside. What
 * they must never do is invent a number: a counter the kernel does not expose reads as unavailable,
 * because a zero that means "could not look" would subtract cleanly from its pair and lie.
 */
class SampleTest {

  @TempDir private Path machine;

  @Test
  @DisplayName("a sample reads the measured core out of the kernel's files")
  void a_core_is_sampled() throws IOException {
    write("sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq", "3400000\n");
    write("sys/class/thermal/thermal_zone0/type", "acpitz\n");
    write("sys/class/thermal/thermal_zone0/temp", "27800\n");
    write("sys/class/thermal/thermal_zone1/type", "x86_pkg_temp\n");
    write("sys/class/thermal/thermal_zone1/temp", "54000\n");
    write("proc/schedstat", "version 15\ntimestamp 4300000000\ncpu2 7 0 91234 11 22 33 44 55 66\n");
    write("proc/stat", "cpu  1 2 3 4 5 6 7 8 9 10\ncpu2 10 0 20 30 0 1 2 77 0 0\n");

    final List<Setting> sample = Sample.ofCore(machine, 2);

    assertThat(named(sample, "core frequency kHz").actual()).isEqualTo("3400000");
    assertThat(named(sample, "package temperature").actual())
        .as("the package's zone, found by type rather than by position")
        .isEqualTo("54000");
    assertThat(named(sample, "core context switches").actual()).isEqualTo("91234");
    assertThat(named(sample, "core steal ticks").actual()).isEqualTo("77");
    assertThat(sample)
        .allSatisfy(setting -> assertThat(setting.status()).isEqualTo(Setting.Status.OK));
  }

  @Test
  @DisplayName("a run that chose no core has nothing to sample and says so")
  void an_unpinned_run_samples_nothing() {
    assertThat(Sample.ofCore(machine, -1))
        .isNotEmpty()
        .allSatisfy(setting -> assertThat(setting.status()).isEqualTo(Setting.Status.UNAVAILABLE));
  }

  @Test
  @DisplayName("a machine that exposes nothing reads as unavailable, never as zero")
  void a_bare_machine_is_unavailable() {
    assertThat(Sample.ofCore(machine, 2))
        .isNotEmpty()
        .allSatisfy(setting -> assertThat(setting.status()).isEqualTo(Setting.Status.UNAVAILABLE));
    assertThat(Sample.ofThisThread(machine))
        .isNotEmpty()
        .allSatisfy(setting -> assertThat(setting.status()).isEqualTo(Setting.Status.UNAVAILABLE));
  }

  @Test
  @DisplayName("a thread's switch counts come from its own status file")
  void the_thread_reads_its_own_counts() throws IOException {
    write(
        "proc/thread-self/status",
        "Name:\tengine\nvoluntary_ctxt_switches:\t150\nnonvoluntary_ctxt_switches:\t7\n");

    final List<Setting> sample = Sample.ofThisThread(machine);

    assertThat(named(sample, "thread voluntary switches").actual()).isEqualTo("150");
    assertThat(named(sample, "thread involuntary switches").actual()).isEqualTo("7");
  }

  private static Setting named(final List<Setting> sample, final String name) {
    return sample.stream()
        .filter(setting -> setting.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no setting called " + name));
  }

  private void write(final String path, final String content) throws IOException {
    final Path file = machine.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}

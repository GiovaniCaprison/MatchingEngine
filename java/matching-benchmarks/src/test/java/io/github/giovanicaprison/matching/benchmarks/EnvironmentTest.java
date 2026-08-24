package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The probes read a machine out of a directory, so a directory is what they are tested against. The
 * distinction that matters here is between a setting that is wrong and one the kernel never
 * exposed: the first invalidates a run and the second is what a laptop looks like.
 */
class EnvironmentTest {

  @TempDir private Path machine;

  @Test
  @DisplayName("a machine set up as the methodology asks is measurement grade")
  void a_controlled_machine_passes() throws IOException {
    controlled();

    final Environment environment = Environment.reading(machine);

    assertThat(environment.failures()).isEmpty();
    assertThat(environment.measurementGrade()).isTrue();
    assertThat(named(environment, "processor").actual()).isEqualTo("Intel Xeon Platinum 8375C");
    assertThat(named(environment, "instance").actual()).isEqualTo("i-0abc123");
    assertThat(named(environment, "isolated cores").actual()).isEqualTo("2-15");
  }

  @Test
  @DisplayName("a setting that did not take fails the run and says what it wanted")
  void a_wrong_setting_is_a_failure() throws IOException {
    controlled();
    write("sys/devices/system/cpu/cpu0/cpufreq/scaling_governor", "powersave\n");

    final Environment environment = Environment.reading(machine);

    assertThat(environment.measurementGrade()).isFalse();
    assertThat(environment.failures())
        .singleElement()
        .satisfies(
            setting -> {
              assertThat(setting.name()).isEqualTo("scaling governor");
              assertThat(setting.status()).isEqualTo(Setting.Status.WRONG);
              assertThat(setting.expected()).isEqualTo("performance");
              assertThat(setting.actual()).isEqualTo("powersave");
            });
  }

  @Test
  @DisplayName("a setting the kernel does not expose reads as unavailable, not as wrong")
  void a_missing_setting_is_unavailable() {
    final Environment environment = Environment.reading(machine.resolve("nothing-here"));

    assertThat(environment.measurementGrade()).isFalse();
    assertThat(environment.failures())
        .allSatisfy(setting -> assertThat(setting.status()).isEqualTo(Setting.Status.UNAVAILABLE));
    assertThat(named(environment, "runtime").actual())
        .as("the runtime is read from the runtime, whatever the machine looks like")
        .isNotNull();
  }

  @Test
  @DisplayName("paranoia lower than the instruments need is not a failure")
  void a_stricter_setting_than_required_passes() throws IOException {
    controlled();
    write("proc/sys/kernel/perf_event_paranoid", "-1\n");

    assertThat(named(Environment.reading(machine), "perf event paranoia").status())
        .isEqualTo(Setting.Status.OK);
  }

  @Test
  @DisplayName("this machine can be probed without arranging anything")
  void the_real_machine_can_be_read() {
    final Environment environment = Environment.ofThisMachine();

    assertThat(environment.settings()).isNotEmpty();
    assertThat(named(environment, "collector").actual()).isNotBlank();
    assertThat(named(environment, "buffer bounds checks").actual()).isIn("true", "false");
  }

  private static Setting named(final Environment environment, final String name) {
    return environment.settings().stream()
        .filter(setting -> setting.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no setting called " + name));
  }

  private void controlled() throws IOException {
    write(
        "proc/cmdline",
        "BOOT_IMAGE=/boot/vmlinuz isolcpus=2-15 nohz_full=2-15 rcu_nocbs=2-15"
            + " processor.max_cstate=1 intel_idle.max_cstate=0\n");
    write("proc/version", "Linux version 6.8.0-45-generic\n");
    write(
        "proc/cpuinfo",
        "processor\t: 0\nmodel name\t: Intel Xeon Platinum 8375C\nprocessor\t: 1\n");
    write(
        "proc/meminfo",
        "MemTotal:       263852812 kB\nSwapTotal:      0 kB\nHugePages_Total:    1024\n");
    write("sys/devices/virtual/dmi/id/board_asset_tag", "i-0abc123\n");
    write("sys/devices/system/cpu/cpu0/cpufreq/scaling_governor", "performance\n");
    write("sys/devices/system/cpu/intel_pstate/no_turbo", "1\n");
    write("sys/devices/system/clocksource/clocksource0/current_clocksource", "tsc\n");
    write("proc/sys/kernel/perf_event_paranoid", "1\n");
    write("proc/sys/kernel/kptr_restrict", "0\n");
    write("sys/kernel/mm/transparent_hugepage/enabled", "always [madvise] never\n");
  }

  private void write(final String path, final String content) throws IOException {
    final Path file = machine.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}

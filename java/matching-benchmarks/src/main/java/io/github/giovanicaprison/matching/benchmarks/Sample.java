package io.github.giovanicaprison.matching.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The machine's transient state at one moment of a run, around the measured core.
 *
 * <p>The environment says what the machine is; a sample says what it was doing. One is taken before
 * the run and one after, because the difference between the pair is the claim: a frequency that
 * moved, a package that warmed, a core the kernel kept scheduling over, or time a hypervisor took
 * are each a run that measured something other than the engine, and none of them shows in a single
 * reading.
 *
 * <p>Everything here is recorded rather than required. A sample cannot fail a run on its own,
 * because what a value means depends on its pair, and judging pairs is analysis's business rather
 * than the harness's.
 *
 * <p>The root is a parameter for the same reason it is on {@link Environment}: the probes are
 * tested against a directory that stands in for a machine, and in a run it is the real one.
 */
final class Sample {

  private Sample() {}

  /**
   * What the kernel says about one core, read from the same files a person would.
   *
   * <p>Context switches are the times the scheduler ran on the core, from {@code /proc/schedstat},
   * and steal is the core's line in {@code /proc/stat}, which only moves under a hypervisor. A run
   * that asked for no core in particular has nothing to sample, and says so rather than sampling a
   * core the engine may never have touched.
   */
  static List<Setting> ofCore(final Path root, final int core) {
    if (core < 0) {
      return ofCore(null, null, null, null);
    }
    return ofCore(
        KernelFiles.firstLine(
            root, "sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_cur_freq"),
        packageTemperature(root),
        token(root, "proc/schedstat", core, 3),
        token(root, "proc/stat", core, 8));
  }

  private static List<Setting> ofCore(
      final String frequency, final String temperature, final String switches, final String steal) {
    return List.of(
        Setting.recorded("core frequency kHz", "cpufreq/scaling_cur_freq", frequency),
        Setting.recorded("package temperature", "thermal_zone*/temp", temperature),
        Setting.recorded("core context switches", "proc/schedstat", switches),
        Setting.recorded("core steal ticks", "proc/stat", steal));
  }

  /**
   * The calling thread's own scheduling counts, which only it can read at the right moment.
   *
   * <p>An involuntary switch is the kernel preempting the thread, and on the engine's thread every
   * one of them lands in somebody's tail. The thread reads its own file because the count belongs
   * to a thread rather than a core, and by the time anything else could look the thread is gone.
   */
  static List<Setting> ofThisThread(final Path root) {
    final String status = "proc/thread-self/status";
    return List.of(
        Setting.recorded(
            "thread voluntary switches",
            status,
            KernelFiles.keyed(root, status, "voluntary_ctxt_switches")),
        Setting.recorded(
            "thread involuntary switches",
            status,
            KernelFiles.keyed(root, status, "nonvoluntary_ctxt_switches")));
  }

  /** The temperature of the zone the processor package reports, where a machine has one. */
  private static String packageTemperature(final Path root) {
    final Path thermal = root.resolve("sys/class/thermal");
    if (!Files.isDirectory(thermal)) {
      return null;
    }
    try (Stream<Path> zones = Files.list(thermal)) {
      for (final Path zone : zones.sorted().toList()) {
        if ("x86_pkg_temp".equals(KernelFiles.firstLine(zone.resolve("type")))) {
          return KernelFiles.firstLine(zone.resolve("temp"));
        }
      }
    } catch (final IOException e) {
      return null;
    }
    return null;
  }

  /** One field of the line describing the core, in a file of space separated counters. */
  private static String token(final Path root, final String path, final int core, final int field) {
    return KernelFiles.read(root.resolve(path))
        .flatMap(
            text ->
                text.lines()
                    .filter(line -> line.startsWith("cpu" + core + " "))
                    .map(line -> line.split("\\s+"))
                    .filter(tokens -> tokens.length > field)
                    .map(tokens -> tokens[field])
                    .findFirst())
        .orElse(null);
  }
}

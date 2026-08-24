package io.github.giovanicaprison.matching.benchmarks;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The machine and the runtime, as they actually are at the moment of a run.
 *
 * <p>Read from the kernel's own files rather than from a setup script's exit code. A script that
 * ran is not the same claim as a setting that took, and the difference is a run that looks
 * controlled and is not.
 *
 * <p>The root is a parameter so that the probes can be tested against a directory that stands in
 * for a machine. In a run it is the real one.
 *
 * <p>Whether the measured core is one the kernel was told to leave alone cannot be probed until a
 * core has been chosen, so {@link #isolationOf(int)} answers it separately.
 *
 * <p>A setting a kernel does not expose reads as unavailable rather than wrong. The distinction
 * matters when the same harness runs on a laptop to be developed on and on metal to be believed.
 */
public final class Environment {

  private static final String CMDLINE = "proc/cmdline";
  private static final String CPUINFO = "proc/cpuinfo";

  private final Path root;
  private final List<Setting> settings;

  private Environment(final Path root) {
    this.root = root;
    this.settings = probe();
  }

  /** The machine this process is running on. */
  public static Environment ofThisMachine() {
    return new Environment(Path.of("/"));
  }

  /** A machine described by a directory, which is how the probes are tested. */
  public static Environment reading(final Path root) {
    return new Environment(root);
  }

  public List<Setting> settings() {
    return settings;
  }

  /** Nothing a measurement needs is missing or contradicted. */
  public boolean measurementGrade() {
    return failures().isEmpty();
  }

  /** The settings that would make a number unbelievable, in the order they were probed. */
  public List<Setting> failures() {
    return settings.stream().filter(setting -> !setting.satisfied()).toList();
  }

  /**
   * Whether one core is the kernel's to schedule on.
   *
   * <p>Pinning to a core the kernel still puts timers, work queues and callbacks on buys almost
   * nothing, and the cost arrives in the tail where it is easy to blame on the engine.
   *
   * @param core the core the engine will run on
   */
  public List<Setting> isolationOf(final int core) {
    return List.of(
        isolates("core isolated", "isolcpus", core),
        isolates("core tickless", "nohz_full", core),
        isolates("core callback offloaded", "rcu_nocbs", core));
  }

  private Setting isolates(final String name, final String parameter, final int core) {
    final String list = parameter(parameter);
    if (list == null) {
      return Setting.required(name, CMDLINE, "true", null);
    }
    return Setting.required(
        name, CMDLINE, "true", String.valueOf(CpuList.parse(list).contains(core)));
  }

  private List<Setting> probe() {
    final List<Setting> found = new ArrayList<>();

    found.add(Setting.recorded("kernel", "proc/version", firstLine("proc/version")));
    found.add(Setting.recorded("processor", CPUINFO, keyed(CPUINFO, "model name")));
    found.add(Setting.recorded("cores", CPUINFO, count(CPUINFO, "processor")));
    found.add(Setting.recorded("instance", "dmi/board_asset_tag", instanceId()));
    found.add(Setting.recorded("command line", CMDLINE, firstLine(CMDLINE)));

    // A frequency that drifts turns one distribution into several. Turbo is the larger of the two
    // effects and the governor decides whether anything holds still at all.
    found.add(
        Setting.required(
            "scaling governor",
            "cpufreq/scaling_governor",
            "performance",
            firstLine("sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")));
    found.add(
        Setting.required(
            "turbo disabled",
            "intel_pstate/no_turbo",
            "1",
            firstLine("sys/devices/system/cpu/intel_pstate/no_turbo")));

    // Deep idle states cost microseconds to leave, and they leave in the middle of a measurement.
    found.add(
        Setting.required("processor max c-state", CMDLINE, "1", parameter("processor.max_cstate")));
    found.add(
        Setting.required(
            "intel idle max c-state", CMDLINE, "0", parameter("intel_idle.max_cstate")));

    // The three that keep the kernel off the measured core. Which core is the driver's business.
    found.add(Setting.recorded("isolated cores", CMDLINE, parameter("isolcpus")));
    found.add(Setting.recorded("tickless cores", CMDLINE, parameter("nohz_full")));
    found.add(Setting.recorded("callback offloaded cores", CMDLINE, parameter("rcu_nocbs")));

    // A clock read is in the measured region, so its cost has to be the cheap one.
    found.add(
        Setting.required(
            "clocksource",
            "clocksource0/current_clocksource",
            "tsc",
            firstLine("sys/devices/system/clocksource/clocksource0/current_clocksource")));

    // The counters need one and the profiler needs the other.
    found.add(
        Setting.required(
            "perf event paranoia",
            "kernel/perf_event_paranoid",
            "1",
            atMostOne("proc/sys/kernel/perf_event_paranoid")));
    found.add(
        Setting.required(
            "kernel pointers readable",
            "kernel/kptr_restrict",
            "0",
            firstLine("proc/sys/kernel/kptr_restrict")));

    found.add(
        Setting.recorded(
            "transparent huge pages",
            "transparent_hugepage/enabled",
            firstLine("sys/kernel/mm/transparent_hugepage/enabled")));
    found.add(
        Setting.recorded(
            "huge pages reserved", "proc/meminfo", keyed("proc/meminfo", "HugePages_Total")));
    found.add(
        Setting.required("swap off", "proc/meminfo", "0 kB", keyed("proc/meminfo", "SwapTotal")));

    found.add(Setting.recorded("runtime", "jvm", runtime()));
    found.add(Setting.recorded("collector", "jvm", collector()));
    found.add(Setting.recorded("runtime arguments", "jvm", arguments()));
    found.add(
        Setting.recorded(
            "buffer bounds checks",
            "agrona.disable.bounds.checks",
            String.valueOf(!Boolean.getBoolean("agrona.disable.bounds.checks"))));

    return List.copyOf(found);
  }

  private String instanceId() {
    return firstLine("sys/devices/virtual/dmi/id/board_asset_tag");
  }

  /** Paranoia at or below one is what the instruments need, so a lower value is not a failure. */
  private String atMostOne(final String path) {
    final String value = firstLine(path);
    if (value == null) {
      return null;
    }
    try {
      return Integer.parseInt(value.strip()) <= 1 ? "1" : value.strip();
    } catch (final NumberFormatException e) {
      return value.strip();
    }
  }

  private String parameter(final String name) {
    final String cmdline = firstLine(CMDLINE);
    if (cmdline == null) {
      return null;
    }
    for (final String token : cmdline.split("\\s+")) {
      if (token.startsWith(name + "=")) {
        return token.substring(name.length() + 1);
      }
    }
    return null;
  }

  private String keyed(final String path, final String key) {
    return read(path)
        .flatMap(
            text ->
                text.lines()
                    .filter(line -> line.startsWith(key) && line.contains(":"))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip())
                    .findFirst())
        .orElse(null);
  }

  private String count(final String path, final String key) {
    return read(path)
        .map(
            text ->
                String.valueOf(text.lines().filter(line -> line.startsWith(key + "\t")).count()))
        .orElse(null);
  }

  private String firstLine(final String path) {
    return read(path).flatMap(text -> text.lines().findFirst()).map(String::strip).orElse(null);
  }

  private Optional<String> read(final String path) {
    final Path file = root.resolve(path);
    if (!Files.isReadable(file) || Files.isDirectory(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(file));
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  private static String runtime() {
    return System.getProperty("java.vm.name")
        + " "
        + System.getProperty("java.vm.version")
        + " ("
        + System.getProperty("java.vm.vendor")
        + ")";
  }

  private static String collector() {
    return String.join(
        ", ",
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(java.lang.management.GarbageCollectorMXBean::getName)
            .toList());
  }

  private static String arguments() {
    return String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments());
  }
}

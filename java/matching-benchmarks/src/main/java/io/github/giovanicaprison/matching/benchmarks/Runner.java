package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One run of one implementation, from the command line.
 *
 * <p>One implementation to a process, which is not a preference. Two engines loaded and used in one
 * runtime make the call site that dispatches to them megamorphic, and every number after that
 * describes a dispatch this project would never ship. So the implementation is named on the command
 * line and constructed by reflection, and the harness keeps no compile time knowledge of any of
 * them.
 *
 * <p>The rate is offered, not requested. A run that could not hold it says so in its own record
 * rather than quietly reporting a queue as a latency.
 */
public final class Runner {

  private static final String[] KNOWN = {
    "implementation",
    "label",
    "log",
    "rate",
    "commands",
    "warmup",
    "resting",
    "seed",
    "results",
    "cores",
    "composition",
    "auction-every",
    "counters",
    "shift-at",
    "shift-composition"
  };

  private Runner() {}

  public static void main(final String[] arguments) throws Exception {
    final Arguments parsed = Arguments.of(arguments, KNOWN);
    final String implementation = parsed.required("implementation");
    final String label = parsed.text("label", simpleName(implementation));

    final FlowParameters flow = flowOf(parsed);
    // A real session converted by matching-calibration replays from a file; otherwise the flow is
    // generated here. Either way the manifest records which, since findings are conditional on it.
    final String source = parsed.text("log", "");
    final CommandLog log =
        source.isEmpty() ? FlowGenerator.generate(flow) : CommandLog.readFrom(Path.of(source));
    final MeasurementParameters parameters = parametersOf(parsed);

    final Run run = Run.create(Path.of(parsed.text("results", "results")), label);
    final Environment environment = Environment.ofThisMachine();
    final Manifest manifest =
        Manifest.of(
            run,
            implementation,
            Path.of("."),
            environment,
            isolationOf(environment, parameters.cores()),
            flow,
            source.isEmpty() ? "generated" : source);
    manifest.write();

    final Measurement.Outcome outcome = Measurement.run(log, factoryOf(implementation), parameters);
    outcome.writeTo(run);
    writeTypes(run, log);

    report(run, manifest, environment, parameters, outcome);
  }

  private static FlowParameters flowOf(final Arguments parsed) {
    final FlowParameters.Composition composition =
        compositionOf(parsed.text("composition", "standard"));
    final int shiftAt = (int) parsed.number("shift-at", 0);
    final FlowParameters.Shift shift =
        shiftAt == 0
            ? null
            : new FlowParameters.Shift(
                shiftAt,
                compositionOf(parsed.text("shift-composition", "standard")),
                FlowParameters.Placement.standard());
    return new FlowParameters(
        parsed.number("seed", 1),
        (int) parsed.number("commands", 1_000_000),
        (int) parsed.number("resting", 5_000),
        FlowParameters.Instrument.standard(),
        composition,
        FlowParameters.Placement.standard(),
        (int) parsed.number("auction-every", 0),
        shift);
  }

  private static FlowParameters.Composition compositionOf(final String name) {
    return "limit-and-market".equals(name)
        ? FlowParameters.Composition.limitAndMarketOnly()
        : FlowParameters.Composition.standard();
  }

  private static MeasurementParameters parametersOf(final Arguments parsed) {
    return new MeasurementParameters(
        parsed.number("rate", 100_000),
        (int) parsed.number("warmup", 200_000),
        1 << 24,
        1 << 24,
        coresOf(parsed.text("cores", "")),
        countersOf(parsed.text("counters", "")));
  }

  /**
   * Which counters to bracket the reported region with, from the catalogue by name.
   *
   * <p>The default set fits any processor's slots. Asking for more is how an investigation run
   * chases a mechanism, and a set too large to schedule shows up as multiplexed in the record
   * rather than as a refusal here.
   */
  private static Set<Counter> countersOf(final String names) {
    if (names.isBlank()) {
      return Counter.few();
    }
    final EnumSet<Counter> wanted = EnumSet.noneOf(Counter.class);
    for (final String name : names.split(",")) {
      wanted.add(Counter.valueOf(name.strip()));
    }
    return wanted;
  }

  /**
   * One byte per recorded command saying what the command was, so analysis can attribute latency
   * per command type (NFR-4.4) by joining position for position with the timings. Written from the
   * log after the run, so nothing on the measured path knows it exists.
   */
  private static void writeTypes(final Run run, final CommandLog log) {
    final int recorded = log.count() - log.measuredFrom();
    final java.nio.ByteBuffer out =
        java.nio.ByteBuffer.allocate(8 + Integer.BYTES + recorded)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
    out.put("METYPES1".getBytes(java.nio.charset.StandardCharsets.UTF_8)).putInt(recorded);
    for (int command = log.measuredFrom(); command < log.count(); command++) {
      out.put((byte) log.templateId(command));
    }
    try {
      java.nio.file.Files.write(run.file("types.bin"), out.array());
    } catch (final java.io.IOException e) {
      throw new java.io.UncheckedIOException("cannot write the command types", e);
    }
  }

  /**
   * Whether the engine's core is one the kernel was told to leave alone, which cannot be judged
   * until a core has been chosen. A run that chose none has nothing to judge and is graded on that,
   * since an unpinned engine is on whatever core the scheduler liked at the time.
   */
  private static List<Setting> isolationOf(
      final Environment environment, final MeasurementParameters.Cores cores) {
    if (cores.engine() == MeasurementParameters.UNPINNED) {
      return List.of(Setting.required("engine core", "cores", "chosen", null));
    }
    return environment.isolationOf(cores.engine());
  }

  /** Three core numbers, driver first, or nothing at all on a machine nobody controls. */
  private static MeasurementParameters.Cores coresOf(final String cores) {
    if (cores.isBlank()) {
      return MeasurementParameters.Cores.anywhere();
    }
    final String[] parts = cores.split(",");
    if (parts.length != 3) {
      throw new IllegalArgumentException("--cores wants three, driver first: " + cores);
    }
    return new MeasurementParameters.Cores(
        Integer.parseInt(parts[0].strip()),
        Integer.parseInt(parts[1].strip()),
        Integer.parseInt(parts[2].strip()));
  }

  private static MatchingEngineFactory factoryOf(final String name) throws Exception {
    return (MatchingEngineFactory) Class.forName(name).getDeclaredConstructor().newInstance();
  }

  private static String simpleName(final String implementation) {
    final int dot = implementation.lastIndexOf('.');
    return dot < 0 ? implementation : implementation.substring(dot + 1);
  }

  /**
   * What the run was, on the way out.
   *
   * <p>Enough to see whether it is worth keeping and nothing that replaces reading the directory.
   * The grade and the two kept-up lines are the ones that decide it.
   */
  private static void report(
      final Run run,
      final Manifest manifest,
      final Environment environment,
      final MeasurementParameters parameters,
      final Measurement.Outcome outcome) {
    final long commands = outcome.timings().recorded() - parameters.compilationWarmup();
    System.out.printf("run          %s%n", run.id());
    System.out.printf("grade        %s%n", manifest.grade());
    System.out.printf(
        "unmet        %d of %d settings%n",
        environment.failures().size(), environment.settings().size());
    System.out.printf("offered      %,d a second%n", parameters.ratePerSecond());
    System.out.printf("reported     %,d commands%n", commands);
    System.out.printf("harness      %s%n", outcome.harnessKeptUp() ? "kept up" : "fell behind");
    System.out.printf(
        "placement    %s%n",
        outcome.placement().isEmpty() ? "unpinned" : outcome.placement().toString());
    System.out.printf("events       %,d%n", outcome.verification().events());
    System.out.printf("counts       %s%n", outcome.verification().countsByName());
    System.out.printf("reasons      %s%n", outcome.verification().reasons());
    System.out.printf(
        "counters     %s%n",
        outcome.counted().isEmpty() ? "unavailable" : outcome.counted().toString());
    System.out.printf(
        "service      p50 %,d  p99 %,d  p999 %,d  max %,d ns%n",
        outcome.timings().service().getValueAtPercentile(50),
        outcome.timings().service().getValueAtPercentile(99),
        outcome.timings().service().getValueAtPercentile(99.9),
        outcome.timings().service().getMaxValue());
    System.out.printf(
        "response     p50 %,d  p99 %,d  p999 %,d  max %,d ns%n",
        outcome.timings().response().getValueAtPercentile(50),
        outcome.timings().response().getValueAtPercentile(99),
        outcome.timings().response().getValueAtPercentile(99.9),
        outcome.timings().response().getMaxValue());
    System.out.printf(
        "queued       high water %,d bytes in, %,d bytes out%n",
        outcome.commandsQueuedHighWater(), outcome.eventsQueuedHighWater());
    System.out.printf("artifacts    %s%n", run.directory());
  }
}

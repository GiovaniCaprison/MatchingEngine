package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.flow.FlowParameters;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What a run was: which engine, on which machine, from which commit, over which flow.
 *
 * <p>Written before the measurement rather than after, so a run that dies still says what it was
 * attempting. Every figure in the write up is traceable to one of these.
 *
 * @param run the directory and identity this manifest describes
 * @param implementation which engine was measured
 * @param commit the source it was built from
 * @param commandLine how it was invoked, including the flags that decide a runtime's behaviour
 * @param environment the machine and runtime as verified
 * @param isolation whether the engine's core is one the kernel leaves alone, judged for the core
 *     the run chose, or the fact that it chose none
 * @param flow the input, by seed and composition rather than by copy
 * @param flowSource where the input came from: {@code generated}, or the file a real session was
 *     converted into, in which case the composition below describes nothing and the file does
 */
public record Manifest(
    Run run,
    String implementation,
    String commit,
    String commandLine,
    Environment environment,
    List<Setting> isolation,
    FlowParameters flow,
    String flowSource) {

  /**
   * Whether the numbers from this run can be believed.
   *
   * <p>A run on an uncontrolled machine is still useful while an implementation is being written,
   * so it is allowed and labelled rather than refused. The label is what stops it turning into a
   * result. The measured core counts as much as the machine does: a run on a controlled box that
   * pinned its engine to a core the kernel still schedules on, or to no core at all, looks
   * controlled and is not.
   */
  public String grade() {
    return environment.measurementGrade() && isolation.stream().allMatch(Setting::satisfied)
        ? "measurement"
        : "exploratory";
  }

  public static Manifest of(
      final Run run,
      final String implementation,
      final Path repository,
      final Environment environment,
      final List<Setting> isolation,
      final FlowParameters flow,
      final String flowSource) {
    return new Manifest(
        run,
        implementation,
        Git.head(repository),
        invocation(),
        environment,
        isolation,
        flow,
        flowSource);
  }

  public String toJson() {
    final Json json =
        new Json()
            .object()
            .field("run", run.id())
            .field("startedAt", run.startedAt().toString())
            .field("implementation", implementation)
            .field("commit", commit)
            .field("commandLine", commandLine)
            .field("grade", grade());

    json.object("flow")
        .field("source", flowSource)
        .field("seed", flow.seed())
        .field("commands", flow.commands())
        .field("restingOrders", flow.restingOrders());
    instrument(json);
    composition(json, "composition", flow.composition());
    placement(json, "placement", flow.placement());
    if (flow.shift() != null) {
      json.object("shift").field("atCommand", flow.shift().atCommand());
      composition(json, "composition", flow.shift().composition());
      placement(json, "placement", flow.shift().placement());
      json.end();
    }
    json.end();

    json.array("environment");
    environment.settings().forEach(setting -> setting.writeTo(json));
    json.end();

    json.array("isolation");
    isolation.forEach(setting -> setting.writeTo(json));
    json.end();

    return json.end().toString();
  }

  public void write() {
    final Path file = run.file("manifest.json");
    try {
      Files.writeString(file, toJson());
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot write the manifest to " + file, e);
    }
  }

  private void instrument(final Json json) {
    final FlowParameters.Instrument instrument = flow.instrument();
    json.object("instrument")
        .field("tickSize", instrument.tickSize())
        .field("lotSize", instrument.lotSize())
        .field("minPrice", instrument.minPrice())
        .field("maxPrice", instrument.maxPrice())
        .field("priceScale", instrument.priceScale())
        .field("bandWidth", instrument.bandWidth())
        .field("openingReference", instrument.openingReference())
        .field("allocation", instrument.allocation().name())
        .end();
  }

  private static void composition(
      final Json json, final String name, final FlowParameters.Composition composition) {
    json.object(name)
        .field("aggressive", composition.aggressive())
        .field("market", composition.market())
        .field("cancel", composition.cancel())
        .field("replace", composition.replace())
        .field("massCancel", composition.massCancel())
        .field("iceberg", composition.iceberg())
        .field("stop", composition.stop())
        .field("immediateOrCancel", composition.immediateOrCancel())
        .field("fillOrKill", composition.fillOrKill())
        .field("postOnly", composition.postOnly())
        .field("minimumQuantity", composition.minimumQuantity())
        .field("selfMatch", composition.selfMatch())
        .end();
  }

  private static void placement(
      final Json json, final String name, final FlowParameters.Placement placement) {
    json.object(name)
        .field("depthTicks", placement.depthTicks())
        .field("maximumLots", placement.maximumLots())
        .field("participants", placement.participants())
        .end();
  }

  /** The flags a runtime was given, which decide as much as the code does. */
  private static String invocation() {
    return String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments())
        + " "
        + System.getProperty("sun.java.command", "");
  }
}

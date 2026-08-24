package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.flow.FlowParameters;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * @param flow the input, by seed and composition rather than by copy
 */
public record Manifest(
    Run run,
    String implementation,
    String commit,
    String commandLine,
    Environment environment,
    FlowParameters flow) {

  /**
   * Whether the numbers from this run can be believed.
   *
   * <p>A run on an uncontrolled machine is still useful while an implementation is being written,
   * so it is allowed and labelled rather than refused. The label is what stops it turning into a
   * result.
   */
  public String grade() {
    return environment.measurementGrade() ? "measurement" : "exploratory";
  }

  public static Manifest of(
      final Run run,
      final String implementation,
      final Path repository,
      final Environment environment,
      final FlowParameters flow) {
    return new Manifest(run, implementation, Git.head(repository), invocation(), environment, flow);
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
        .field("seed", flow.seed())
        .field("commands", flow.commands())
        .field("restingOrders", flow.restingOrders());
    instrument(json);
    composition(json);
    placement(json);
    json.end();

    json.array("environment");
    for (final Setting setting : environment.settings()) {
      json.object()
          .field("name", setting.name())
          .field("source", setting.source())
          .field("expected", setting.expected())
          .field("actual", setting.actual())
          .field("status", setting.status().name())
          .end();
    }
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

  private void composition(final Json json) {
    final FlowParameters.Composition composition = flow.composition();
    json.object("composition")
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

  private void placement(final Json json) {
    final FlowParameters.Placement placement = flow.placement();
    json.object("placement")
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

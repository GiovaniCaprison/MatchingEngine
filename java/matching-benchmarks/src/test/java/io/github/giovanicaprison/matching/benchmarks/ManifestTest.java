package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.flow.FlowParameters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manifest is the only thing that makes a number traceable, so what it has to carry is the
 * input in full and the machine as found rather than as intended.
 */
class ManifestTest {

  @TempDir private Path results;

  @Test
  @DisplayName("a manifest carries the flow it ran and the machine it ran on")
  void a_manifest_says_what_a_run_was() {
    final Manifest manifest = manifest();

    manifest.write();
    final Path file = manifest.run().file("manifest.json");

    // The composition's numbers are measured and will move when the session is measured again, so
    // what has to be here is every parameter, not any particular value of one.
    assertThat(file).content().contains("\"seed\": 4242").contains("\"cancel\":");
    assertThat(file).content().contains("\"allocation\": \"PRICE_TIME\"");
    assertThat(file).content().contains("\"implementation\": \"naive-java\"");
    assertThat(file).content().contains("\"name\": \"clocksource\"");
  }

  @Test
  @DisplayName("a run on a machine nobody controlled is labelled, not refused")
  void the_grade_follows_the_environment() {
    assertThat(manifest().grade()).isEqualTo("exploratory");
  }

  @Test
  @DisplayName("the manifest is written before anything is measured")
  void the_manifest_exists_up_front() throws Exception {
    final Manifest manifest = manifest();
    assertThat(Files.exists(manifest.run().file("manifest.json"))).isFalse();

    manifest.write();

    assertThat(manifest.run().file("manifest.json")).isNotEmptyFile();
  }

  @Test
  @DisplayName("a core nobody isolated is written down and keeps the run exploratory")
  void the_grade_follows_the_measured_core() {
    final Manifest manifest =
        Manifest.of(
            Run.create(results, "naive-java"),
            "naive-java",
            results.resolve("no-checkout"),
            Environment.reading(results.resolve("no-machine")),
            List.of(Setting.required("core isolated", "isolcpus", "true", "false")),
            FlowParameters.standard(4242, 10_000),
            "generated");

    manifest.write();

    assertThat(manifest.grade()).isEqualTo("exploratory");
    assertThat(manifest.run().file("manifest.json"))
        .content()
        .contains("\"isolation\"")
        .contains("\"core isolated\"");
  }

  @org.junit.jupiter.api.Test
  @DisplayName("a manifest carries the regime shift when the flow has one")
  void the_shift_is_recorded() {
    final FlowParameters flow = FlowParameters.standard(1, 1_000);
    final Manifest manifest =
        Manifest.of(
            Run.create(results, "naive-java"),
            "naive-java",
            results.resolve("no-checkout"),
            Environment.reading(results.resolve("no-machine")),
            List.of(),
            new FlowParameters(
                flow.seed(),
                flow.commands(),
                flow.restingOrders(),
                flow.instrument(),
                flow.composition(),
                flow.placement(),
                0,
                new FlowParameters.Shift(
                    500,
                    FlowParameters.Composition.limitAndMarketOnly(),
                    FlowParameters.Placement.standard())),
            "generated");

    manifest.write();

    assertThat(manifest.run().file("manifest.json"))
        .content()
        .contains("\"shift\"")
        .contains("\"atCommand\": 500");
  }

  private Manifest manifest() {
    return Manifest.of(
        Run.create(results, "naive-java"),
        "naive-java",
        results.resolve("no-checkout"),
        Environment.reading(results.resolve("no-machine")),
        List.of(),
        FlowParameters.standard(4242, 10_000),
        "generated");
  }
}

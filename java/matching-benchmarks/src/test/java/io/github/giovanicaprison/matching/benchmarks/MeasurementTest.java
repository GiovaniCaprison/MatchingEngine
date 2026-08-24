package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the driver has to get right: every command applied once, every event accounted for, and the
 * difference between an engine being slow and a queue being long.
 *
 * <p>These are properties rather than performance. A wall clock number from a laptop means nothing,
 * but the relationship between two of them is the thing the harness exists to expose, so the
 * assertions are on relationships and are given generous room.
 */
class MeasurementTest {

  private static final int EVENTS_PER_COMMAND = 2;

  @TempDir private Path results;

  @Test
  @DisplayName("every command reaches the engine once and every event reaches the verifier")
  void nothing_is_lost_or_repeated() {
    final CommandLog log = log(4_000);
    final CountingEngine engine = new CountingEngine(EVENTS_PER_COMMAND);

    final Measurement.Outcome outcome = Measurement.run(log, engine, parameters(200_000));

    assertThat(outcome.commands()).isEqualTo(log.count());
    assertThat(engine.commands()).isEqualTo(log.count());
    assertThat(outcome.verification().events()).isEqualTo((long) log.count() * EVENTS_PER_COMMAND);
    assertThat(outcome.timings().recorded()).isEqualTo(log.count() - log.measuredFrom());
    assertThat(outcome.harnessKeptUp()).isTrue();
  }

  @Test
  @DisplayName("the same engine on the same log produces the same digest")
  void a_run_is_reproducible_in_what_it_produced() {
    final CommandLog log = log(2_000);

    final long first =
        Measurement.run(log, new CountingEngine(EVENTS_PER_COMMAND), parameters(200_000))
            .verification()
            .digest();
    final long second =
        Measurement.run(log, new CountingEngine(EVENTS_PER_COMMAND), parameters(200_000))
            .verification()
            .digest();

    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("an engine that stalls shows up as queueing, not as samples nobody took")
  void the_tail_survives_a_stall() {
    // The point of an open loop driver. A harness that waited for the previous command would take
    // no
    // sample during the stall and none of the ones behind it, and the tail would disappear.
    final CommandLog log = log(20_000);
    final CountingEngine engine = new CountingEngine(1, 2_000, 300_000);

    final Measurement.Outcome outcome = Measurement.run(log, engine, parameters(100_000));

    final long serviceAt99 = outcome.timings().service().getValueAtPercentile(99);
    final long responseAt99 = outcome.timings().response().getValueAtPercentile(99);
    assertThat(responseAt99)
        .as("commands behind a stall are quick to serve and slow to answer")
        .isGreaterThan(serviceAt99 * 10);
    assertThat(outcome.timings().queued().getMaxValue())
        .as("the wait is on the ring, where it can be seen")
        .isGreaterThan(outcome.timings().service().getValueAtPercentile(50) * 10);
  }

  @Test
  @DisplayName("a run writes its numbers where they can be read later")
  void a_run_leaves_its_artifacts() {
    final Run run = Run.create(results, "counting");

    Measurement.run(log(2_000), new CountingEngine(EVENTS_PER_COMMAND), parameters(200_000))
        .writeTo(run);

    assertThat(run.file("measurement.json")).isNotEmptyFile();
    assertThat(run.file("verification.json")).isNotEmptyFile();
    assertThat(run.file("latency.hdr")).isNotEmptyFile();
    assertThat(run.file("timings.bin")).isNotEmptyFile();
    assertThat(run.file("measurement.json"))
        .content()
        .contains("\"harnessKeptUp\": true")
        .contains("\"OrderAccepted\"")
        .contains("\"service\"")
        .contains("\"response\"");
  }

  @Test
  @DisplayName("the offered rate is the rate the driver holds")
  void the_rate_is_offered_whatever_happens() {
    final CommandLog log = log(10_000);

    final Measurement.Outcome outcome =
        Measurement.run(log, new CountingEngine(1), parameters(500_000));

    // Two microseconds of slack a command, which a laptop can be behind by and metal cannot.
    assertThat(outcome.timings().offered().getValueAtPercentile(99)).isLessThan(2_000L);
  }

  private static MeasurementParameters parameters(final long rate) {
    return new MeasurementParameters(rate, 0, 1 << 20, 1 << 20);
  }

  private static CommandLog log(final int commands) {
    return FlowGenerator.generate(
        new FlowParameters(
            42,
            commands,
            200,
            FlowParameters.Instrument.standard(),
            FlowParameters.Composition.standard(),
            FlowParameters.Placement.standard()));
  }
}

package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

  // The injected stall the two stall tests lean on: large enough to dominate a quiet machine's
  // tail and small enough to keep the suite quick. A machine whose own noise reaches the stall
  // cannot see the signal, so those tests abstain there rather than measure the scheduler; the
  // first shared runner this ran on had a baseline tail forty times the stall.
  private static final int STALL_EVERY = 2_000;
  private static final int STALL_NANOS = 300_000;
  private static final int QUIET_ENOUGH = STALL_NANOS / 3;

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
    // no sample during the stall and none of the ones behind it, and the tail would disappear.
    final CommandLog log = log(20_000);
    final CountingEngine engine = new CountingEngine(1, STALL_EVERY, STALL_NANOS);

    final Measurement.Outcome outcome = Measurement.run(log, engine, parameters(100_000));

    final long serviceAt99 = outcome.timings().service().getValueAtPercentile(99);
    final long responseAt99 = outcome.timings().response().getValueAtPercentile(99);
    assumeTrue(serviceAt99 < QUIET_ENOUGH, "this machine's noise is louder than the stall");
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
        .contains("\"response\"")
        .contains("\"sampledBefore\"")
        .contains("\"sampledAfter\"");
  }

  @Test
  @DisplayName("the driver offers at its own rate whatever the engine is doing")
  void the_rate_is_offered_whatever_happens() {
    // The open loop property, as a relation rather than a wall clock bound. An engine that stalls
    // makes commands wait, and the question is where they wait: on the ring, or in a driver that
    // has stopped offering. A number of nanoseconds asserted here would measure this laptop, and
    // README says as much about wall clock assertions in a unit suite. A relation still needs a
    // floor of quiet, so a box whose baseline tail reaches the stall abstains: it cannot say who
    // caused the waiting.
    final CommandLog log = log(20_000);

    final Measurement.Outcome quick =
        Measurement.run(log, new CountingEngine(1), parameters(100_000));
    final Measurement.Outcome stalling =
        Measurement.run(log, new CountingEngine(1, STALL_EVERY, STALL_NANOS), parameters(100_000));

    final long stalled = stalling.timings().response().getValueAtPercentile(99);
    final long unstalled = quick.timings().response().getValueAtPercentile(99);
    assumeTrue(unstalled < QUIET_ENOUGH, "this machine's noise is louder than the stall");
    assertThat(stalled)
        .as("the stalling engine has to be visibly worse, or this proves nothing")
        .isGreaterThan(unstalled * 10);

    assertThat(stalling.timings().offered().getValueAtPercentile(99))
        .as("the driver kept offering, so the waiting happened on the ring and not in the harness")
        .isLessThan(stalled / 10);
  }

  private static MeasurementParameters parameters(final long rate) {
    return new MeasurementParameters(
        rate, 0, 1 << 20, 1 << 20, MeasurementParameters.Cores.anywhere(), Counter.few());
  }

  @Test
  @DisplayName("a run asked to place its threads records where they ended up")
  void placement_is_recorded_rather_than_assumed() {
    // Two outcomes are honest: the threads went where they were asked, or the platform has no such
    // call and the run says so. Silence is the one thing that would not be.
    final MeasurementParameters pinned =
        new MeasurementParameters(
            200_000, 0, 1 << 20, 1 << 20, new MeasurementParameters.Cores(1, 2, 3), Counter.few());

    final Measurement.Outcome outcome = Measurement.run(log(2_000), new CountingEngine(1), pinned);

    assertThat(outcome.placement()).hasSize(3);
    assertThat(outcome.placement())
        .allSatisfy(
            setting ->
                assertThat(setting.status())
                    .isIn(Setting.Status.OK, Setting.Status.UNAVAILABLE, Setting.Status.WRONG));
    assertThat(outcome.commands()).isEqualTo(log(2_000).count());
  }

  @Test
  @DisplayName("a run that asked for no core in particular claims nothing about placement")
  void an_unpinned_run_claims_nothing() {
    final Measurement.Outcome outcome =
        Measurement.run(log(2_000), new CountingEngine(1), parameters(200_000));

    assertThat(outcome.placement()).isEmpty();
    assertThat(outcome.placedAsAsked()).isTrue();
  }

  @Test
  @DisplayName("a run reports what the hardware counted, or that it could not be counted")
  void counters_are_reported_or_absent() {
    // Two honest outcomes again: counts for the region, or nothing because the platform has no
    // perf_event_open. What would not be honest is a number nobody can attribute to a region.
    final Measurement.Outcome outcome =
        Measurement.run(log(2_000), new CountingEngine(1), parameters(200_000));

    if (Counters.available()) {
      assertThat(outcome.counted()).containsOnlyKeys(Counter.few().toArray(Counter[]::new));
      assertThat(outcome.counted().values()).allSatisfy(count -> assertThat(count).isNotNegative());
    } else {
      assertThat(outcome.counted()).isEmpty();
      assertThat(outcome.countersMultiplexed()).isFalse();
    }
  }

  private static CommandLog log(final int commands) {
    return FlowGenerator.generate(
        new FlowParameters(
            42,
            commands,
            200,
            FlowParameters.Instrument.standard(),
            FlowParameters.Composition.standard(),
            FlowParameters.Placement.standard(),
            0));
  }
}

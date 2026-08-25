package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.flow.FlowGenerator;
import io.github.giovanicaprison.matching.flow.FlowParameters;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the generated flow is allowed to spend on being refused.
 *
 * <p>A flow that rejects a quarter of what it offers is measuring the validation path, and it got
 * there once: before the command mix was measured against a real session it refused 25% of
 * everything, almost all of it cancels arriving after their order had traded. Nothing said so,
 * because a rejection is a perfectly good outcome and a run reporting thousands of them looks like
 * it is working.
 *
 * <p>So the budget is a test rather than a note. It holds against the engine that is written to be
 * obviously right, since a rejection rate is a property of the flow and the engine together.
 */
class FlowBudgetTest {

  private static final String NAIVE = "io.github.giovanicaprison.matching.naive.NaiveEngineFactory";

  /**
   * Cancel-too-late is real and a real venue sees a little of it. AAPL's session says a few
   * percent.
   */
  private static final double REFUSED_AT_MOST = 0.06;

  /** Executions per order entered. The session measured showed about one in fifteen. */
  private static final double EXECUTED_AT_LEAST = 0.02;

  private static final double EXECUTED_AT_MOST = 0.15;

  @Test
  @DisplayName("the flow spends almost nothing on being refused")
  void the_flow_stays_within_its_budget() throws Exception {
    final CommandLog log = FlowGenerator.generate(FlowParameters.standard(11, 60_000));

    final Measurement.Outcome outcome =
        Measurement.run(log, factory(), MeasurementParameters.at(200_000, cores()));

    final Map<String, Long> counts = outcome.verification().countsByName();
    final long accepted = counts.getOrDefault("OrderAccepted", 0L);
    final long refused = counts.getOrDefault("OrderRejected", 0L);
    final long executed = counts.getOrDefault("OrderExecuted", 0L);

    assertThat(accepted).as("a flow this size has to get orders in").isGreaterThan(10_000);
    assertThat((double) refused / (accepted + refused))
        .as(
            "refusals, which are %d of %d commands offered: %s",
            refused, accepted + refused, outcome.verification().reasons())
        .isLessThan(REFUSED_AT_MOST);
    assertThat((double) executed / accepted)
        .as("executions per order entered, which decides how much matching there is to measure")
        .isBetween(EXECUTED_AT_LEAST, EXECUTED_AT_MOST);
  }

  private static MeasurementParameters.Cores cores() {
    return MeasurementParameters.Cores.anywhere();
  }

  private static MatchingEngineFactory factory() throws Exception {
    return (MatchingEngineFactory) Class.forName(NAIVE).getDeclaredConstructor().newInstance();
  }
}

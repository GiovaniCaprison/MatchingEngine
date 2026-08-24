package io.github.giovanicaprison.matching.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.giovanicaprison.matching.api.EventSink;
import io.github.giovanicaprison.matching.protocol.RemoveReason;
import io.github.giovanicaprison.matching.protocol.SessionState;
import io.github.giovanicaprison.matching.protocol.Side;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The replay: commands encoded from text, events rendered back to text, and the two compared.
 *
 * <p>The engine ids in these scripts are deliberately not one and two. A runner that only worked
 * when an implementation numbered from one would be a runner that tests id allocation.
 */
class CorpusRunnerTest {

  private static final String FIXTURE =
      """
      INSTRUMENT tick=5 lot=1 scale=4 min=1 max=1000000 band=500 open=100000 alloc=PRICE_TIME

      SESSION  CONTINUOUS
      STATE    CONTINUOUS

      NEW      BUY LIMIT GTC 100000 50
      ACCEPTED #1
      RESTED   #1 BUY 100000 50

      NEW      SELL LIMIT IOC 100000 50
      ACCEPTED #2
      EXECUTED @1 aggressor=#2 resting=#1 100000 50
      """;

  private final Events events = new Events();

  @Test
  @DisplayName("a fixture the engine satisfies passes")
  void a_matching_run_passes() {
    final CorpusRunner.Result result =
        CorpusRunner.run(FixtureParser.parse("example", FIXTURE), engineEmitting(50));

    assertThat(result.passed()).as(result.describe()).isTrue();
    assertThat(result.emitted())
        .containsExactly(
            "STATE CONTINUOUS",
            "ACCEPTED #1",
            "RESTED #1 BUY 100000 50",
            "ACCEPTED #2",
            "EXECUTED @1 aggressor=#2 resting=#1 100000 50");
  }

  @Test
  @DisplayName("aligned columns are not part of the comparison")
  void whitespace_is_not_compared() {
    final String aligned =
        FIXTURE.replace("RESTED   #1 BUY 100000 50", "RESTED #1     BUY 100000 50");

    assertThat(
            CorpusRunner.run(FixtureParser.parse("example", aligned), engineEmitting(50)).passed())
        .isTrue();
  }

  @Test
  @DisplayName("a difference is reported at the line it happens, with the run as a fixture")
  void a_difference_names_its_line() {
    final CorpusRunner.Result result =
        CorpusRunner.run(FixtureParser.parse("example", FIXTURE), engineEmitting(40));

    assertThat(result.passed()).isFalse();
    assertThat(result.firstDifference()).isEqualTo(2);
    assertThat(result.describe())
        .contains("differs at output line 3")
        .contains("expected: RESTED #1 BUY 100000 50")
        .contains("actual:   RESTED #1 BUY 100000 40")
        .contains("NEW      BUY LIMIT GTC 100000 50");
  }

  @Test
  @DisplayName("an engine that emits nothing differs at the first line the fixture expects")
  void a_silent_engine_fails() {
    final CorpusRunner.Result result =
        CorpusRunner.run(FixtureParser.parse("example", FIXTURE), new ScriptedEngine(List.of()));

    assertThat(result.firstDifference()).isZero();
    assertThat(result.describe()).contains("actual:   (nothing)");
  }

  @Test
  @DisplayName("a command against an order the engine never accepted is refused")
  void an_unbound_reference_is_refused() {
    final String fixture =
        """
        INSTRUMENT tick=5 lot=1 scale=4 min=1 max=1000000 band=500 open=100000 alloc=PRICE_TIME
        NEW    BUY LIMIT GTC 100000 50
        CANCEL #1
        """;

    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                CorpusRunner.run(
                    FixtureParser.parse("example", fixture), new ScriptedEngine(List.of())))
        .withMessageContaining("#1 was never accepted");
  }

  @Test
  @DisplayName("a removal renders its reason")
  void a_removal_renders_its_reason() {
    final String fixture =
        """
        INSTRUMENT tick=5 lot=1 scale=4 min=1 max=1000000 band=500 open=100000 alloc=PRICE_TIME
        NEW      BUY LIMIT GTC 100000 50
        ACCEPTED #1
        RESTED   #1 BUY 100000 50
        CANCEL   #1
        REMOVED  #1 50 CANCELLED
        """;
    final ScriptedEngine engine =
        new ScriptedEngine(
            List.of(
                List.of(),
                List.of(events.accepted(701, 1), events.rested(701, Side.BUY, 100_000, 50)),
                List.of(events.removed(701, 50, RemoveReason.CANCELLED))));

    final CorpusRunner.Result result =
        CorpusRunner.run(FixtureParser.parse("example", fixture), engine);

    assertThat(result.passed()).as(result.describe()).isTrue();
    assertThat(engine.commandsSeen()).isEqualTo(3);
  }

  private ScriptedEngine engineEmitting(final long restingQuantity) {
    final List<Consumer<EventSink>> definition = List.of();
    final List<Consumer<EventSink>> session = List.of(events.state(SessionState.CONTINUOUS));
    final List<Consumer<EventSink>> resting =
        List.of(events.accepted(701, 1), events.rested(701, Side.BUY, 100_000, restingQuantity));
    final List<Consumer<EventSink>> crossing =
        List.of(events.accepted(702, 2), events.executed(9_001, 702, 701, 100_000, 50));
    return new ScriptedEngine(List.of(definition, session, resting, crossing));
  }
}

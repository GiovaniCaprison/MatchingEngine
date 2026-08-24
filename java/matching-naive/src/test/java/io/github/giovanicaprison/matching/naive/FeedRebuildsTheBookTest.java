package io.github.giovanicaprison.matching.naive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import io.github.giovanicaprison.matching.conformance.ConsumerBook;
import io.github.giovanicaprison.matching.conformance.Corpus;
import io.github.giovanicaprison.matching.conformance.CorpusRunner;
import io.github.giovanicaprison.matching.conformance.Fixture;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Inside the package, because the question needs both books.
 *
 * <p>The public interface can say whether a stream is followable, and the corpus checks that for
 * every fixture. It cannot say whether the book a consumer ends up with is the book the engine has,
 * because only the engine has the second one. So this test reaches for it, and that is the whole
 * reason it is not a boundary test.
 *
 * <p>Compared after each command rather than after each event. Between the events of one command
 * the engine is part way through a mutation, and the two books are supposed to agree when it has
 * finished.
 */
class FeedRebuildsTheBookTest {

  @DisplayName("FR-8.2 the book rebuilt from the events is the book the engine is holding")
  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void the_feed_is_sufficient(final Fixture fixture) {
    final Engines engines = new Engines();

    final CorpusRunner.Result result =
        CorpusRunner.run(
            fixture,
            engines,
            (command, rebuilt) ->
                assertThat(visible(engines.engine))
                    .as("after %s", command.text())
                    .isEqualTo(rebuilt.entries()));

    assertThat(result.passed()).as(result.describe()).isTrue();
  }

  /**
   * The engine's book as the feed describes it: queue order, and displayed quantity only.
   *
   * <p>Queue order rather than list order, since a replenished tranche goes to the back of its
   * price without moving in any list. Two books that agree on quantities and disagree on position
   * would allocate differently and still pass a comparison that only added up.
   */
  private static List<ConsumerBook.Entry> visible(final NaiveEngine engine) {
    return engine.resting().stream()
        .sorted(Comparator.comparingLong(Order::arrival))
        .map(
            order ->
                new ConsumerBook.Entry(order.id(), order.side(), order.price(), order.displayed()))
        .toList();
  }

  static Stream<Fixture> fixtures() {
    return Corpus.fixtures().stream();
  }

  /** Keeps the engine the runner built, which is the only way to get at it afterwards. */
  private static final class Engines implements MatchingEngineFactory {

    private NaiveEngine engine;

    @Override
    public MatchingEngine create(final EventPublisher events) {
      engine = new NaiveEngine(events);
      return engine;
    }
  }
}

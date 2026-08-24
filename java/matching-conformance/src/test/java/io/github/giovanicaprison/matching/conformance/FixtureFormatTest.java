package io.github.giovanicaprison.matching.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The format's rules, which every runner in every language has to agree on. */
class FixtureFormatTest {

  private static final String INSTRUMENT =
      "INSTRUMENT tick=5 lot=1 scale=4 min=1 max=1000000 band=500 open=100000 alloc=PRICE_TIME";

  @Test
  @DisplayName("directives and output verbs share no name")
  void the_two_vocabularies_are_disjoint() {
    // What lets a fixture put an event on the line below the command that caused it. Two names in
    // common and a file would need a marker saying which half of the fixture a line belongs to.
    final Set<String> shared =
        new HashSet<>(Stream.of(Directive.values()).map(Enum::name).toList());
    shared.retainAll(Stream.of(Verb.values()).map(Enum::name).toList());

    assertThat(shared).isEmpty();
  }

  @Test
  @DisplayName("comments and blank lines are not part of a fixture")
  void comments_and_blank_lines_are_ignored() {
    final Fixture fixture =
        FixtureParser.parse(
            "example",
            """
            # a stop cascade during an iceberg replenishment

            %s

            SESSION CONTINUOUS
            STATE   CONTINUOUS
            """
                .formatted(INSTRUMENT));

    assertThat(fixture.commands()).hasSize(2);
    assertThat(fixture.expectedOutput()).containsExactly("STATE   CONTINUOUS");
  }

  @Test
  @DisplayName("a line that is neither a directive nor a verb is refused with its position")
  void an_unknown_word_is_refused() {
    assertThatExceptionOfType(FixtureParser.MalformedFixture.class)
        .isThrownBy(() -> FixtureParser.parse("example", INSTRUMENT + "\nSNAPSHOT #1\n"))
        .withMessageContaining("example:2")
        .withMessageContaining("SNAPSHOT");
  }

  @Test
  @DisplayName("a fixture defines its instrument first and once")
  void the_instrument_comes_first_and_only_once() {
    assertThatExceptionOfType(FixtureParser.MalformedFixture.class)
        .isThrownBy(() -> FixtureParser.parse("example", "SESSION CONTINUOUS\n"))
        .withMessageContaining("first command must be INSTRUMENT");

    assertThatExceptionOfType(FixtureParser.MalformedFixture.class)
        .isThrownBy(() -> FixtureParser.parse("example", INSTRUMENT + "\n" + INSTRUMENT + "\n"))
        .withMessageContaining("defined once");
  }

  @Test
  @DisplayName("a command missing its positional words is refused")
  void a_short_command_is_refused() {
    assertThatExceptionOfType(FixtureParser.MalformedFixture.class)
        .isThrownBy(() -> FixtureParser.parse("example", INSTRUMENT + "\nNEW BUY LIMIT GTC\n"))
        .withMessageContaining("NEW needs at least 5");
  }

  @Test
  @DisplayName("a command keeps its words in order and its text for reprinting")
  void a_command_carries_its_words_and_its_text() {
    final Fixture fixture =
        FixtureParser.parse("example", INSTRUMENT + "\nNEW  SELL  MARKET  IOC  -  50 p=2\n");
    final Fixture.Command order = fixture.commands().get(1);

    assertThat(order.directive()).isEqualTo(Directive.NEW);
    assertThat(order.arguments()).containsExactly("SELL", "MARKET", "IOC", "-", "50", "p=2");
    assertThat(order.text()).isEqualTo("NEW  SELL  MARKET  IOC  -  50 p=2");
    assertThat(Arrays.asList(order.text().split("\\s+"))).hasSize(7);
  }
}

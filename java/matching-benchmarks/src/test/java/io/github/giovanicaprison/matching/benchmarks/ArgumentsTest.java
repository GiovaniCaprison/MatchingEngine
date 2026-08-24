package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A run is reproduced by typing what its manifest says, so the command line has to be strict. */
class ArgumentsTest {

  @Test
  @DisplayName("a flag nobody recognises is refused rather than ignored")
  void a_misspelled_flag_is_refused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Arguments.of(new String[] {"--raet", "100"}, "rate"))
        .withMessageContaining("raet is not an argument");
  }

  @Test
  @DisplayName("a flag with no value is refused")
  void a_flag_needs_a_value() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Arguments.of(new String[] {"--rate"}, "rate"))
        .withMessageContaining("expected --name value");
  }

  @Test
  @DisplayName("what was given is read and what was not falls back")
  void values_and_fallbacks() {
    final Arguments arguments = Arguments.of(new String[] {"--rate", "250000"}, "rate", "label");

    assertThat(arguments.number("rate", 1)).isEqualTo(250_000);
    assertThat(arguments.number("commands", 7)).isEqualTo(7);
    assertThat(arguments.text("label", "unnamed")).isEqualTo("unnamed");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> arguments.required("label"))
        .withMessageContaining("--label is required");
  }
}

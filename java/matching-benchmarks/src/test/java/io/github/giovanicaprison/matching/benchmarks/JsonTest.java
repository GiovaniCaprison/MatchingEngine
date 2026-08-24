package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An artifact nobody can parse is an artifact nobody can use. */
class JsonTest {

  @Test
  @DisplayName("nested objects and arrays come out separated")
  void nesting_is_written_correctly() {
    final String json =
        new Json()
            .object()
            .field("run", "20260824T101500Z-naive")
            .object("flow")
            .field("seed", 7L)
            .field("cancel", 0.35)
            .end()
            .array("environment")
            .object()
            .field("name", "clocksource")
            .field("ok", true)
            .end()
            .object()
            .field("name", "governor")
            .field("expected", (String) null)
            .end()
            .end()
            .end()
            .toString();

    assertThat(json)
        .isEqualTo(
            """
            {
              "run": "20260824T101500Z-naive",
              "flow": {
                "seed": 7,
                "cancel": 0.35
              },
              "environment": [
                {
                  "name": "clocksource",
                  "ok": true
                },
                {
                  "name": "governor",
                  "expected": null
                }
              ]
            }
            """);
  }

  @Test
  @DisplayName("a value that would break the format is escaped")
  void quoting_survives_awkward_values() {
    final String json =
        new Json()
            .object()
            .field("commandLine", "-XX:+UseEpsilonGC \"quoted\"\tand\\a backslash")
            .field("newline", "one\ntwo")
            .end()
            .toString();

    assertThat(json)
        .contains("\\\"quoted\\\"\\tand\\\\a backslash")
        .contains("\"newline\": \"one\\ntwo\"");
  }

  @Test
  @DisplayName("an empty object stays on one line")
  void an_empty_scope_is_not_padded() {
    assertThat(new Json().object().object("counts").end().end().toString())
        .isEqualTo(
            """
            {
              "counts": {}
            }
            """);
  }

  @Test
  @DisplayName("an unfinished document is refused rather than written")
  void an_open_scope_is_an_error() {
    final Json json = new Json().object().object("flow");

    assertThatIllegalStateException()
        .isThrownBy(json::toString)
        .withMessageContaining("still open");
  }
}

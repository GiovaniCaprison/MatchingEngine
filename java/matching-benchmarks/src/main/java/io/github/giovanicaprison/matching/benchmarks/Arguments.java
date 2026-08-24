package io.github.giovanicaprison.matching.benchmarks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The command line, which a manifest records verbatim.
 *
 * <p>Named arguments rather than positions, because a run is reproduced by reading its manifest and
 * typing what it says. Anything unrecognised is refused rather than ignored: a misspelled flag that
 * silently takes a default is a run measuring something nobody asked for.
 */
final class Arguments {

  private final Map<String, String> values = new LinkedHashMap<>();

  private Arguments() {}

  static Arguments of(final String[] arguments, final String... known) {
    final Arguments parsed = new Arguments();
    for (int at = 0; at < arguments.length; at += 2) {
      final String name = arguments[at];
      if (!name.startsWith("--") || at + 1 >= arguments.length) {
        throw new IllegalArgumentException("expected --name value, got " + name);
      }
      final String key = name.substring(2);
      if (!java.util.Arrays.asList(known).contains(key)) {
        throw new IllegalArgumentException(
            key + " is not an argument. Known: " + String.join(", ", known));
      }
      parsed.values.put(key, arguments[at + 1]);
    }
    return parsed;
  }

  String text(final String key, final String fallback) {
    return values.getOrDefault(key, fallback);
  }

  String required(final String key) {
    final String value = values.get(key);
    if (value == null) {
      throw new IllegalArgumentException("--" + key + " is required");
    }
    return value;
  }

  long number(final String key, final long fallback) {
    final String value = values.get(key);
    return value == null ? fallback : Long.parseLong(value);
  }
}

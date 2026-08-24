package io.github.giovanicaprison.matching.gates;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** The names the SBE schema defines, and the messages among them. */
final class Schema {

  private static final String FILE = "schema/matching-engine.xml";

  private Schema() {}

  /** Message, type, enum, field and valid value names alike, which is what a document may quote. */
  static Set<String> names() {
    return matches("name=\"([^\"]+)\"");
  }

  static Set<String> messages() {
    return matches("<sbe:message\\s+name=\"([^\"]+)\"");
  }

  private static Set<String> matches(final String pattern) {
    return Pattern.compile(pattern)
        .matcher(Repository.read(FILE))
        .results()
        .map(result -> result.group(1))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}

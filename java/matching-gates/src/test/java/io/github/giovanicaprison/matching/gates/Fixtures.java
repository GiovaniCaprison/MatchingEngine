package io.github.giovanicaprison.matching.gates;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The corpus, read as claims.
 *
 * <p>A rule fixture opens with the id of the requirement it states, so the claim and the rule are
 * the same line and cannot drift apart. That is where coverage is read from now that no test method
 * names a requirement in its own source: one test replays the whole directory, and the directory is
 * the suite.
 */
final class Fixtures {

  /** A line that expects an event, which is what makes a fixture check anything at all. */
  private static final Pattern VERB = Pattern.compile("(?m)^([A-Z][A-Z]+)\\b");

  private Fixtures() {}

  record Fixture(Path file, String title, boolean expectsOutput) {

    Set<String> requirements() {
      return Requirements.ID
          .matcher(title)
          .results()
          .map(java.util.regex.MatchResult::group)
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    String describe() {
      return Repository.describe(file) + " \"" + title + "\"";
    }
  }

  static List<Fixture> all(final Set<String> verbs) {
    final List<Fixture> found = new ArrayList<>();
    for (final Path file : Repository.filesUnder("corpus", ".txt")) {
      final String content = Repository.contentOf(file);
      found.add(new Fixture(file, title(content), expectsOutput(content, verbs)));
    }
    return found;
  }

  static Set<String> requirementsClaimed(final Set<String> verbs) {
    return all(verbs).stream()
        .flatMap(fixture -> fixture.requirements().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /** The first comment line, which is what the fixture says it is for. */
  private static String title(final String content) {
    return content
        .lines()
        .map(String::strip)
        .filter(line -> line.startsWith("#"))
        .map(line -> line.substring(1).strip())
        .findFirst()
        .orElse("");
  }

  private static boolean expectsOutput(final String content, final Set<String> verbs) {
    return VERB.matcher(content).results().anyMatch(result -> verbs.contains(result.group(1)));
  }
}

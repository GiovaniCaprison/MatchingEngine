package io.github.giovanicaprison.matching.gates;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code REQUIREMENTS.md} read as data.
 *
 * <p>The document is the source of truth, so the gates parse it rather than holding a copy. A
 * second list of ids in Java would be one more thing to leave stale, which is the failure these
 * gates exist to catch.
 */
final class Requirements {

  /** Matches an id wherever it is cited, including in prose and in a source comment. */
  static final Pattern ID = Pattern.compile("\\b(?:FR|VR|NFR)-\\d+\\.\\d+\\b");

  static final Pattern PRINCIPLE_ID = Pattern.compile("\\bP-\\d+\\b");

  private static final Map<String, Set<String>> MECHANISMS = parse();

  private Requirements() {}

  /** Every id the document lists, with the mechanisms named against it. */
  static Map<String, Set<String>> mechanisms() {
    return MECHANISMS;
  }

  static Set<String> ids() {
    return MECHANISMS.keySet();
  }

  /** The ids the document says are shown to hold by a unit test. */
  static Set<String> coveredByUnitTests() {
    return withMechanism("unit");
  }

  /** The ids the document says are held by judgement, and so must not be claimed by a test. */
  static Set<String> withoutTests() {
    return withMechanism("review");
  }

  /**
   * The mechanisms the document's own preamble names, which are the ones {@code TESTING.md}
   * defines.
   */
  static Set<String> documentedMechanisms() {
    final String text = Repository.read("docs/REQUIREMENTS.md");
    final int start = text.indexOf("The mechanism column");
    if (start < 0) {
      throw new IllegalStateException("REQUIREMENTS.md no longer introduces the mechanism column");
    }
    final int end = text.indexOf("\n\n", start);
    return Pattern.compile("`([a-z]+)`")
        .matcher(text.substring(start, end < 0 ? text.length() : end))
        .results()
        .map(result -> result.group(1))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /** Every principle id the document defines, taken from its headings. */
  static Set<String> principles() {
    return Pattern.compile("(?m)^##\\s+(P-\\d+):")
        .matcher(Repository.read("docs/PRINCIPLES.md"))
        .results()
        .map(result -> result.group(1))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Set<String> withMechanism(final String mechanism) {
    return MECHANISMS.entrySet().stream()
        .filter(entry -> entry.getValue().contains(mechanism))
        .map(Map.Entry::getKey)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Map<String, Set<String>> parse() {
    final Map<String, Set<String>> found = new LinkedHashMap<>();
    for (final String line : Repository.read("docs/REQUIREMENTS.md").lines().toList()) {
      final String trimmed = line.strip();
      if (!trimmed.startsWith("|")) {
        continue;
      }
      final String[] cells = trimmed.split("\\|", -1);
      if (cells.length < 4) {
        continue;
      }
      final Matcher id = ID.matcher(cells[1].strip());
      if (!id.matches()) {
        continue;
      }
      found.put(id.group(), mechanismsIn(cells[3]));
    }
    if (found.isEmpty()) {
      throw new IllegalStateException("no requirements parsed: the document's table shape changed");
    }
    return found;
  }

  private static Set<String> mechanismsIn(final String cell) {
    return Arrays.stream(cell.split(","))
        .map(String::strip)
        .filter(mechanism -> !mechanism.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}

package io.github.giovanicaprison.matching.gates;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Java test sources, read as display names and method bodies.
 *
 * <p>Text rather than a parser or reflection. A parser is a dependency for a job this size, and
 * reflection would only see a body's effects, which is the opposite of what the gate needs: it has
 * to know whether a method asserts anything at all, including when the assertion is missing.
 */
final class JavaTests {

  private static final Pattern DISPLAY_NAME =
      Pattern.compile("@DisplayName\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");

  /** What counts as checking something. A helper called from the body is not visible here. */
  private static final List<String> ASSERTION_MARKERS = List.of("assert", "Assert", "fail(");

  private JavaTests() {}

  record Declaration(Path file, int line, String displayName, String body) {

    boolean assertsSomething() {
      return ASSERTION_MARKERS.stream().anyMatch(body::contains);
    }

    Set<String> requirementsClaimed() {
      return Requirements.ID
          .matcher(displayName)
          .results()
          .map(MatchResult::group)
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    String describe() {
      return Repository.describe(file) + ":" + line + " \"" + displayName + "\"";
    }
  }

  static List<Declaration> all() {
    final List<Declaration> declarations = new ArrayList<>();
    for (final Path file : Repository.testSources()) {
      final String source = Repository.contentOf(file);
      final Matcher displayName = DISPLAY_NAME.matcher(source);
      while (displayName.find()) {
        declarations.add(
            new Declaration(
                file,
                lineOf(source, displayName.start()),
                displayName.group(1),
                bodyAfter(source, displayName.end())));
      }
    }
    return declarations;
  }

  /** Every requirement id claimed by any test, whether or not the document lists it. */
  static Set<String> requirementsClaimed() {
    return all().stream()
        .flatMap(declaration -> declaration.requirementsClaimed().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * The braced block following an annotation, which is the method it annotates. String and
   * character literals are skipped so that a brace inside one does not unbalance the count.
   */
  private static String bodyAfter(final String source, final int from) {
    final int open = source.indexOf('{', from);
    if (open < 0) {
      return "";
    }
    int depth = 0;
    for (int at = open; at < source.length(); at++) {
      final char character = source.charAt(at);
      switch (character) {
        case '"', '\'' -> at = endOfLiteral(source, at, character);
        case '{' -> depth++;
        case '}' -> {
          depth--;
          if (depth == 0) {
            return source.substring(open, at + 1);
          }
        }
        default -> {}
      }
    }
    return source.substring(open);
  }

  private static int endOfLiteral(final String source, final int start, final char quote) {
    for (int at = start + 1; at < source.length(); at++) {
      final char character = source.charAt(at);
      if (character == '\\') {
        at++;
      } else if (character == quote) {
        return at;
      }
    }
    return source.length() - 1;
  }

  private static int lineOf(final String source, final int offset) {
    return (int) source.substring(0, offset).chars().filter(character -> character == '\n').count()
        + 1;
  }
}

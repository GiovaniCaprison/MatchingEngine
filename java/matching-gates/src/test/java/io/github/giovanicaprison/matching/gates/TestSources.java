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
 * Test sources in either language, read as the name a test goes by and the body under it.
 *
 * <p>Text rather than a parser or reflection. A parser is a dependency for a job this size, and
 * reflection would only see a body's effects, which is the opposite of what the gate needs: it has
 * to know whether a method asserts anything at all, including when the assertion is missing.
 *
 * <p>Both languages name a test in prose, which is why they were chosen: a requirement id can sit
 * in the name where a reader of a failure sees it and this can find it. JUnit spells that
 * {@code @DisplayName} and Catch2 spells it {@code TEST_CASE}, and after the name both are the same
 * problem, a braced block to look inside.
 */
final class TestSources {

  private static final Pattern DISPLAY_NAME =
      Pattern.compile("@DisplayName\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");

  private static final Pattern TEST_CASE =
      Pattern.compile("TEST_CASE\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

  /** What counts as checking something. A helper called from the body is not visible here. */
  private static final List<String> ASSERTION_MARKERS =
      List.of("assert", "Assert", "fail(", "CHECK", "REQUIRE");

  private TestSources() {}

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
    collect(declarations, Repository.javaTestSources(), DISPLAY_NAME);
    collect(declarations, Repository.cppTestSources(), TEST_CASE);
    return declarations;
  }

  private static void collect(
      final List<Declaration> declarations, final List<Path> files, final Pattern named) {
    for (final Path file : files) {
      final String source = Repository.contentOf(file);
      final Matcher name = named.matcher(source);
      while (name.find()) {
        declarations.add(
            new Declaration(
                file, lineOf(source, name.start()), name.group(1), bodyAfter(source, name.end())));
      }
    }
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
    final int open = openingBrace(source, from);
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

  /**
   * The brace that opens the block, which is not always the next one in the text.
   *
   * <p>An annotation can carry a brace inside a string, and {@code @ParameterizedTest(name =
   * "{0}")} is the common case. Taking the next brace there reads the format string as the method
   * body and calls a perfectly good test hollow.
   */
  private static int openingBrace(final String source, final int from) {
    for (int at = from; at < source.length(); at++) {
      final char character = source.charAt(at);
      if (character == '"' || character == '\'') {
        at = endOfLiteral(source, at, character);
      } else if (character == '{') {
        return at;
      }
    }
    return -1;
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

package io.github.giovanicaprison.matching.benchmarks;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Writes the JSON a run's artifacts are read from.
 *
 * <p>Analysis is a script reading a directory, so the format has to be something any language
 * parses without argument. Writing it is fifty lines, and a serialisation library in the measured
 * process is a dependency earning nothing: none of this runs while anything is being timed.
 */
final class Json {

  private static final class Scope {

    private final char closing;
    private boolean empty = true;

    private Scope(final char closing) {
      this.closing = closing;
    }
  }

  private final StringBuilder text = new StringBuilder();
  private final Deque<Scope> scopes = new ArrayDeque<>();

  /** An object with no key, which is how an array holds one. */
  Json object() {
    separate();
    open('{', '}');
    return this;
  }

  Json object(final String name) {
    key(name);
    open('{', '}');
    return this;
  }

  Json array(final String name) {
    key(name);
    open('[', ']');
    return this;
  }

  Json end() {
    final Scope scope = scopes.pop();
    if (!scope.empty) {
      newLine();
    }
    text.append(scope.closing);
    return this;
  }

  Json field(final String name, final String value) {
    key(name);
    text.append(value == null ? "null" : quoted(value));
    return this;
  }

  Json field(final String name, final long value) {
    key(name);
    text.append(value);
    return this;
  }

  Json field(final String name, final double value) {
    key(name);
    text.append(value);
    return this;
  }

  Json field(final String name, final boolean value) {
    key(name);
    text.append(value);
    return this;
  }

  @Override
  public String toString() {
    if (!scopes.isEmpty()) {
      throw new IllegalStateException(scopes.size() + " scopes are still open");
    }
    return text + "\n";
  }

  /** A key has already separated itself, so opening a scope must not separate again. */
  private void open(final char opening, final char closing) {
    text.append(opening);
    scopes.push(new Scope(closing));
  }

  private void key(final String name) {
    separate();
    text.append(quoted(name)).append(": ");
  }

  private void separate() {
    final Scope scope = scopes.peek();
    if (scope == null) {
      return;
    }
    if (scope.empty) {
      scope.empty = false;
    } else {
      text.append(',');
    }
    newLine();
  }

  private void newLine() {
    text.append('\n').append("  ".repeat(scopes.size()));
  }

  private static String quoted(final String value) {
    final StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
    for (int at = 0; at < value.length(); at++) {
      final char character = value.charAt(at);
      switch (character) {
        case '"' -> quoted.append("\\\"");
        case '\\' -> quoted.append("\\\\");
        case '\n' -> quoted.append("\\n");
        case '\r' -> quoted.append("\\r");
        case '\t' -> quoted.append("\\t");
        default -> {
          if (character < 0x20) {
            quoted.append(String.format("\\u%04x", (int) character));
          } else {
            quoted.append(character);
          }
        }
      }
    }
    return quoted.append('"').toString();
  }
}

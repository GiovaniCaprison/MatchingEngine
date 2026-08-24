package io.github.giovanicaprison.matching.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the fixture format described in {@code TESTING.md}.
 *
 * <p>Whitespace separated words, one line per command or expected event, so a runner in any
 * language is a small amount of code. That matters more than terseness here: the fixtures are the
 * contract between a Java engine and a C++ one, and a format that is awkward to parse invites two
 * readings of it.
 *
 * <p>A malformed line fails immediately and names the file and the line. A fixture that parses
 * loosely would produce a comparison failure somewhere else entirely.
 */
public final class FixtureParser {

  /** The least number of words each command needs, before any optional ones. */
  private static final Map<Directive, Integer> ARITY = arity();

  private FixtureParser() {}

  public static Fixture parse(final Path file) {
    try {
      return parse(file.getFileName().toString(), Files.readString(file));
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot read fixture " + file, e);
    }
  }

  public static Fixture parse(final String name, final String content) {
    final List<Fixture.Element> elements = new ArrayList<>();
    String title = "";
    int number = 0;
    for (final String raw : content.lines().toList()) {
      number++;
      final String line = raw.strip();
      if (line.startsWith("#")) {
        if (title.isEmpty()) {
          title = line.substring(1).strip();
        }
        continue;
      }
      if (line.isEmpty()) {
        continue;
      }
      elements.add(element(name, number, line));
    }
    final Fixture fixture = new Fixture(name, title, List.copyOf(elements));
    requireInstrumentFirst(fixture);
    return fixture;
  }

  private static Fixture.Element element(final String name, final int number, final String line) {
    final List<String> words = Arrays.asList(line.split("\\s+"));
    final String first = words.getFirst();
    final Directive directive = lookup(Directive.class, first);
    if (directive != null) {
      final List<String> arguments = words.subList(1, words.size());
      if (arguments.size() < ARITY.get(directive)) {
        throw new MalformedFixture(
            name, number, directive + " needs at least " + ARITY.get(directive) + " words");
      }
      return new Fixture.Command(line, number, directive, List.copyOf(arguments));
    }
    if (lookup(Verb.class, first) != null) {
      return new Fixture.Expected(line, number);
    }
    throw new MalformedFixture(name, number, first + " is neither a directive nor an output verb");
  }

  private static void requireInstrumentFirst(final Fixture fixture) {
    final List<Fixture.Command> commands = fixture.commands();
    if (commands.isEmpty() || commands.getFirst().directive() != Directive.INSTRUMENT) {
      throw new MalformedFixture(fixture.name(), 1, "the first command must be INSTRUMENT");
    }
    if (commands.stream()
        .skip(1)
        .anyMatch(command -> command.directive() == Directive.INSTRUMENT)) {
      throw new MalformedFixture(
          fixture.name(), 1, "an instrument is defined once for the life of an engine");
    }
  }

  private static <E extends Enum<E>> E lookup(final Class<E> type, final String word) {
    for (final E value : type.getEnumConstants()) {
      if (value.name().equals(word)) {
        return value;
      }
    }
    return null;
  }

  private static Map<Directive, Integer> arity() {
    final Map<Directive, Integer> arity = new EnumMap<>(Directive.class);
    arity.put(Directive.INSTRUMENT, 1);
    arity.put(Directive.SESSION, 1);
    arity.put(Directive.NEW, 5);
    arity.put(Directive.CANCEL, 1);
    arity.put(Directive.REPLACE, 3);
    arity.put(Directive.MASSCANCEL, 1);
    return Map.copyOf(arity);
  }

  /** A fixture the runner refuses to guess at. */
  public static final class MalformedFixture extends RuntimeException {

    MalformedFixture(final String name, final int line, final String problem) {
      super(name + ":" + line + " " + problem);
    }
  }
}

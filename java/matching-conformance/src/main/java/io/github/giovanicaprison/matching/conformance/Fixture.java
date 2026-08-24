package io.github.giovanicaprison.matching.conformance;

import java.util.List;

/**
 * One parsed fixture: the commands to send and the output they must produce, in the order the file
 * holds them.
 *
 * <p>Elements keep their original text so that a failing run can print the fixture back with the
 * actual events in place, ready to be read and pasted over the file.
 *
 * @param name how the fixture is identified in a failure, normally its file name
 * @param elements commands and expected output interleaved as written
 */
public record Fixture(String name, String title, List<Element> elements) {

  /**
   * The title is what the fixture is for, in words.
   *
   * <p>It is the first comment line of the file, and for a fixture that states one rule it opens
   * with the id of the requirement that rule is. That is where the coverage gate reads it from, so
   * a rule and its claim cannot drift apart: they are the same line.
   */
  @Override
  public String toString() {
    return title.isBlank() ? name : title;
  }

  /** A line of a fixture: either a command to send or an event to expect. */
  public sealed interface Element {

    /** The line as written, without leading or trailing space. */
    String text();
  }

  /**
   * A command line.
   *
   * @param text the line as written
   * @param line where it sits in the file, for a parse failure
   * @param directive which command it is
   * @param arguments the words after the directive
   */
  public record Command(String text, int line, Directive directive, List<String> arguments)
      implements Element {}

  /**
   * An expected output line.
   *
   * @param text the line as written, which is what the comparison uses
   * @param line where it sits in the file
   */
  public record Expected(String text, int line) implements Element {}

  public List<Command> commands() {
    return elements().stream().filter(Command.class::isInstance).map(Command.class::cast).toList();
  }

  /** The blessed output, in order. Nothing about which command produced which line. */
  public List<String> expectedOutput() {
    return elements().stream().filter(Expected.class::isInstance).map(Element::text).toList();
  }
}

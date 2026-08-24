package io.github.giovanicaprison.matching.conformance;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Replays a fixture against an implementation and compares what it emitted to what the fixture says
 * it must emit.
 *
 * <p>The runner is the publisher, and it renders each event as the engine commits it. Reading on
 * the engine's thread is right here and wrong in a measurement: nothing is being timed, and seeing
 * events arrive inside the command that caused them is what lets a failure be printed as the
 * fixture would read once corrected.
 *
 * <p>Claims advance and wrap rather than reusing one offset, so an engine that assumed its events
 * always land in the same place fails here instead of on a ring buffer.
 *
 * <p>Comparison is over words, not characters, so a fixture can align its columns.
 *
 * <p>Every fixture also builds the book a consumer would build from the events, and a stream that
 * cannot be followed fails the fixture whatever its lines say (FR-8.1). Output that reads correctly
 * and describes an impossible book is the failure worth catching: it passes review and breaks a
 * feed.
 */
public final class CorpusRunner implements EventPublisher {

  /** Room for the largest burst one command can produce, with no reason to be tight about it. */
  private static final int CAPACITY = 1 << 20;

  private final MutableDirectBuffer events = new UnsafeBuffer(new byte[CAPACITY]);
  private final References references = new References();
  private final CommandWriter writer = new CommandWriter(references);
  private final ConsumerBook rebuilt = new ConsumerBook();
  private final EventReader reader = new EventReader(references, rebuilt);
  private final Map<Fixture.Command, List<String>> produced = new LinkedHashMap<>();
  private final List<String> emitted = new ArrayList<>();

  private List<String> inFlight = new ArrayList<>();
  private int cursor;
  private int claimed;
  private int claimedLength;

  private CorpusRunner() {}

  /** Runs one fixture. A throw from the engine is a failure of the fixture, not of the harness. */
  public static Result run(final Fixture fixture, final MatchingEngineFactory factory) {
    return run(fixture, factory, (command, rebuilt) -> {});
  }

  /**
   * Runs one fixture, with the rebuilt book offered up after each command.
   *
   * <p>After a command and not after an event: between the events of one command the engine is part
   * way through a mutation, and a book compared there is a book compared mid-sentence.
   */
  public static Result run(
      final Fixture fixture, final MatchingEngineFactory factory, final Quiescent observer) {
    final CorpusRunner runner = new CorpusRunner();
    final MatchingEngine engine = factory.create(runner);
    for (final Fixture.Command command : fixture.commands()) {
      runner.inFlight = runner.produced.computeIfAbsent(command, key -> new ArrayList<>());
      final int length = runner.writer.write(command);
      engine.onCommand(runner.writer.buffer(), 0, length);
      observer.afterCommand(command, runner.rebuilt);
    }
    return new Result(fixture, runner.emitted, runner.produced, runner.rebuilt.problems());
  }

  /** Somewhere to look while the engine is between commands and holding still. */
  public interface Quiescent {

    void afterCommand(Fixture.Command command, ConsumerBook rebuilt);
  }

  @Override
  public int claim(final int length) {
    if (cursor + length > CAPACITY) {
      cursor = 0;
    }
    claimed = cursor;
    claimedLength = length;
    cursor += length;
    return claimed;
  }

  @Override
  public MutableDirectBuffer buffer() {
    return events;
  }

  @Override
  public void commit() {
    final String line = reader.read(events, claimed, claimedLength);
    emitted.add(line);
    inFlight.add(line);
  }

  /** What one fixture did, and how it differs from what it should have done. */
  public record Result(
      Fixture fixture,
      List<String> emitted,
      Map<Fixture.Command, List<String>> byCommand,
      List<String> problems) {

    public boolean passed() {
      return firstDifference() < 0 && problems.isEmpty();
    }

    /** The index of the first line that differs, or minus one when nothing does. */
    public int firstDifference() {
      final List<String> expected = normalised(fixture.expectedOutput());
      for (int at = 0; at < Math.max(expected.size(), emitted.size()); at++) {
        if (!lineAt(expected, at).equals(lineAt(emitted, at))) {
          return at;
        }
      }
      return -1;
    }

    /**
     * The failure, and the fixture as it would read if the engine were right. Reading that diff is
     * the point: a blessed snapshot is worth what the last person to look at it was paying
     * attention to.
     */
    public String describe() {
      if (passed()) {
        return fixture.name() + " passed";
      }
      final StringBuilder said = new StringBuilder(fixture.name());
      final int at = firstDifference();
      if (at >= 0) {
        final List<String> expected = normalised(fixture.expectedOutput());
        said.append(" differs at output line ")
            .append(at + 1)
            .append("\n  expected: ")
            .append(lineAt(expected, at))
            .append("\n  actual:   ")
            .append(lineAt(emitted, at));
      }
      if (!problems.isEmpty()) {
        said.append("\n  emitted a stream a consumer cannot follow:");
        problems.forEach(problem -> said.append("\n    ").append(problem));
      }
      return said.append("\n\nthe run as a fixture:\n\n").append(asFixture()).toString();
    }

    /** The commands as written, each followed by the events it actually produced. */
    public String asFixture() {
      final StringBuilder text = new StringBuilder();
      byCommand.forEach(
          (command, events) -> {
            text.append(command.text()).append('\n');
            events.forEach(event -> text.append(event).append('\n'));
            text.append('\n');
          });
      return text.toString();
    }

    private static String lineAt(final List<String> lines, final int at) {
      return at < lines.size() ? lines.get(at) : "(nothing)";
    }

    private static List<String> normalised(final List<String> lines) {
      return lines.stream().map(line -> String.join(" ", line.split("\\s+"))).toList();
    }
  }
}

package io.github.giovanicaprison.matching.conformance;

import io.github.giovanicaprison.matching.api.EventSink;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.agrona.DirectBuffer;

/**
 * Replays a fixture against an implementation and compares what it emitted to what the fixture says
 * it must emit.
 *
 * <p>The runner is the sink, so it sees events as the engine produces them, inside the command
 * being applied. That is also what lets a failure be printed as the fixture would read once
 * corrected, with each event under the command that caused it.
 *
 * <p>Comparison is over words, not characters, so a fixture can align its columns.
 */
public final class CorpusRunner implements EventSink {

  private final References references = new References();
  private final CommandWriter writer = new CommandWriter(references);
  private final EventReader reader = new EventReader(references);
  private final Map<Fixture.Command, List<String>> produced = new LinkedHashMap<>();
  private final List<String> emitted = new ArrayList<>();

  private List<String> inFlight = new ArrayList<>();

  private CorpusRunner() {}

  /** Runs one fixture. A throw from the engine is a failure of the fixture, not of the harness. */
  public static Result run(final Fixture fixture, final MatchingEngineFactory factory) {
    final CorpusRunner runner = new CorpusRunner();
    final MatchingEngine engine = factory.create(runner);
    for (final Fixture.Command command : fixture.commands()) {
      runner.inFlight = runner.produced.computeIfAbsent(command, key -> new ArrayList<>());
      final int length = runner.writer.write(command);
      engine.onCommand(runner.writer.buffer(), 0, length);
    }
    return new Result(fixture, runner.emitted, runner.produced);
  }

  @Override
  public void onEvent(final DirectBuffer buffer, final int offset, final int length) {
    final String line = reader.read(buffer, offset, length);
    emitted.add(line);
    inFlight.add(line);
  }

  /** What one fixture did, and how it differs from what it should have done. */
  public record Result(
      Fixture fixture, List<String> emitted, Map<Fixture.Command, List<String>> byCommand) {

    public boolean passed() {
      return firstDifference() < 0;
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
      final int at = firstDifference();
      final List<String> expected = normalised(fixture.expectedOutput());
      return fixture.name()
          + " differs at output line "
          + (at + 1)
          + "\n  expected: "
          + lineAt(expected, at)
          + "\n  actual:   "
          + lineAt(emitted, at)
          + "\n\nthe run as a fixture:\n\n"
          + asFixture();
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

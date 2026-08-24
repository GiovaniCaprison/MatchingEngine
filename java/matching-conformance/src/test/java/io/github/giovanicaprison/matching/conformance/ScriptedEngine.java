package io.github.giovanicaprison.matching.conformance;

import io.github.giovanicaprison.matching.api.EventSink;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import java.util.List;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;

/**
 * An engine that emits what it was told to, one entry per command.
 *
 * <p>It ignores the commands entirely, which is what makes it useful: the runner's job is to encode
 * commands, render events and compare lines, and none of that should depend on an engine agreeing.
 */
final class ScriptedEngine implements MatchingEngine, MatchingEngineFactory {

  private final List<List<Consumer<EventSink>>> script;
  private EventSink sink;
  private int commands;

  ScriptedEngine(final List<List<Consumer<EventSink>>> script) {
    this.script = script;
  }

  @Override
  public MatchingEngine create(final EventSink events) {
    this.sink = events;
    return this;
  }

  @Override
  public void onCommand(final DirectBuffer buffer, final int offset, final int length) {
    if (commands < script.size()) {
      script.get(commands).forEach(event -> event.accept(sink));
    }
    commands++;
  }

  int commandsSeen() {
    return commands;
  }
}

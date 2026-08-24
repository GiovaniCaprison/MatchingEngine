package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import io.github.giovanicaprison.matching.protocol.MessageHeaderEncoder;
import io.github.giovanicaprison.matching.protocol.OrderAcceptedEncoder;
import org.agrona.DirectBuffer;

/**
 * An engine only in the sense that it takes commands and publishes events.
 *
 * <p>The driver is what is being tested, so the engine has to be predictable rather than realistic:
 * a fixed number of events per command, and a stall on demand. A real engine would make every
 * assertion here depend on the engine being right.
 */
final class CountingEngine implements MatchingEngine, MatchingEngineFactory {

  private static final int LENGTH =
      MessageHeaderEncoder.ENCODED_LENGTH + OrderAcceptedEncoder.BLOCK_LENGTH;

  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final OrderAcceptedEncoder accepted = new OrderAcceptedEncoder();
  private final int eventsPerCommand;
  private final int stallEvery;
  private final long stallNanos;

  private EventPublisher events;
  private int commands;

  CountingEngine(final int eventsPerCommand) {
    this(eventsPerCommand, 0, 0);
  }

  CountingEngine(final int eventsPerCommand, final int stallEvery, final long stallNanos) {
    this.eventsPerCommand = eventsPerCommand;
    this.stallEvery = stallEvery;
    this.stallNanos = stallNanos;
  }

  @Override
  public MatchingEngine create(final EventPublisher publisher) {
    this.events = publisher;
    return this;
  }

  @Override
  public void onCommand(final DirectBuffer buffer, final int offset, final int length) {
    commands++;
    for (int event = 0; event < eventsPerCommand; event++) {
      final int at = events.claim(LENGTH);
      accepted.wrapAndApplyHeader(events.buffer(), at, header);
      accepted.frame().instrumentId(1).sequence(commands);
      accepted.orderId(commands).clientOrderId(event).participantId(1);
      events.commit();
    }
    if (stallEvery > 0 && commands % stallEvery == 0) {
      stall();
    }
  }

  int commands() {
    return commands;
  }

  private void stall() {
    final long until = System.nanoTime() + stallNanos;
    while (System.nanoTime() < until) {
      Thread.onSpinWait();
    }
  }
}

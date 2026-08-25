package io.github.giovanicaprison.matching.conformance;

import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import io.github.giovanicaprison.matching.flow.CommandLog;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Replays generated flow against an implementation, offering it up between commands.
 *
 * <p>What the corpus cannot do. A fixture checks a state somebody imagined, and the failure worth
 * catching here is the other kind: every operation correct and the structure slowly wrong.
 * Generated flow reaches states nobody wrote down, and the invariants have to hold in all of them.
 *
 * <p>The rebuilt book comes free, since the same reader that renders an event for a fixture also
 * feeds the book a consumer would build. So the feed contract is checked over generated flow as
 * well, which is where it is most likely to break.
 *
 * <p>Offered after each command rather than after each event, because between the events of one
 * command the engine is part way through a mutation and nothing is supposed to hold.
 */
public final class FlowReplay implements io.github.giovanicaprison.matching.api.EventPublisher {

  /** Room for the largest burst one command can produce, with no reason to be tight about it. */
  private static final int CAPACITY = 1 << 20;

  private final MutableDirectBuffer events = new UnsafeBuffer(new byte[CAPACITY]);
  private final References references = new References();
  private final ConsumerBook rebuilt = new ConsumerBook();
  private final EventReader reader = new EventReader(references, rebuilt);

  private int cursor;
  private int claimed;
  private int claimedLength;
  private long emitted;

  private FlowReplay() {}

  /** What holds between commands, and what the caller wants to look at. */
  public interface Invariants {

    /**
     * @param command which command has just been applied, counting from zero
     * @param rebuilt the book a consumer would be holding
     */
    void check(int command, ConsumerBook rebuilt);
  }

  /**
   * Replays a log and checks after every command.
   *
   * @return how many events the engine produced
   */
  public static long replay(
      final CommandLog log, final MatchingEngineFactory factory, final Invariants invariants) {
    final FlowReplay replay = new FlowReplay();
    final MatchingEngine engine = factory.create(replay);
    for (int command = 0; command < log.count(); command++) {
      engine.onCommand(log.buffer(), log.offset(command), log.length(command));
      invariants.check(command, replay.rebuilt);
      if (!replay.rebuilt.problems().isEmpty()) {
        throw new AssertionError(
            "the stream stopped being followable at command "
                + command
                + ":\n  "
                + String.join("\n  ", replay.rebuilt.problems()));
      }
    }
    return replay.emitted;
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
    reader.read(events, claimed, claimedLength);
    emitted++;
  }
}

package io.github.giovanicaprison.matching.conformance;

import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import io.github.giovanicaprison.matching.flow.CommandLog;
import java.io.ByteArrayOutputStream;
import org.agrona.MutableDirectBuffer;

/**
 * Replays a log and keeps every byte the engine published, in order.
 *
 * <p>This is the differential mechanism's half of the bargain: two implementations fed identical
 * generated input, output diffed (NFR-5.1). The corpus compares rendered words, which is what a
 * person blesses; this compares the encoded stream itself, which is what a consumer actually
 * receives, and it is the only mechanism that catches an allocation error nobody thought to write a
 * fixture for. A book that allocated to the wrong order at the same price is internally consistent,
 * and only another engine's reading of the same input disagrees with it.
 */
public final class DifferentialReplay
    implements io.github.giovanicaprison.matching.api.EventPublisher {

  private final ClaimedBuffer events = new ClaimedBuffer();
  private final ByteArrayOutputStream captured = new ByteArrayOutputStream(1 << 24);
  private final byte[] copy = new byte[1 << 16];

  private DifferentialReplay() {}

  /** Every event the engine produced for this log, encoded, concatenated, in order. */
  public static byte[] replay(final CommandLog log, final MatchingEngineFactory factory) {
    final DifferentialReplay replay = new DifferentialReplay();
    final MatchingEngine engine = factory.create(replay);
    for (int command = 0; command < log.count(); command++) {
      engine.onCommand(log.buffer(), log.offset(command), log.length(command));
    }
    return replay.captured.toByteArray();
  }

  @Override
  public int claim(final int length) {
    return events.claim(length);
  }

  @Override
  public MutableDirectBuffer buffer() {
    return events.buffer();
  }

  @Override
  public void commit() {
    events.buffer().getBytes(events.claimed(), copy, 0, events.claimedLength());
    captured.write(copy, 0, events.claimedLength());
  }
}

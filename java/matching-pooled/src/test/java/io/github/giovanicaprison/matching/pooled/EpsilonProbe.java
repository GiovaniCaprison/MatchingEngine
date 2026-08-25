package io.github.giovanicaprison.matching.pooled;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.naive.NaiveEngineFactory;
import java.nio.file.Path;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The body of the allocation proof: a whole log through one engine, in a JVM whose collector never
 * collects.
 *
 * <p>Run under Epsilon with a heap sized to the setup and little more. An engine that allocates
 * nothing in steady state runs to completion and exits cleanly; one that allocates per command
 * exhausts the heap and dies, which is what the control arm demonstrates. Events go into a fixed
 * buffer and are dropped, because the proof is about the engine's memory and capturing output would
 * put the harness's allocation on the engine's bill.
 */
public final class EpsilonProbe {

  private EpsilonProbe() {}

  public static void main(final String[] arguments) {
    final CommandLog log = CommandLog.readFrom(Path.of(arguments[0]));
    final Sink sink = new Sink();
    final MatchingEngine engine =
        arguments[1].equals("pooled")
            ? new PooledEngineFactory().create(sink)
            : new NaiveEngineFactory().create(sink);
    for (int command = 0; command < log.count(); command++) {
      engine.onCommand(log.buffer(), log.offset(command), log.length(command));
    }
  }

  private static final class Sink implements EventPublisher {

    private final MutableDirectBuffer space = new UnsafeBuffer(new byte[1024]);

    @Override
    public int claim(final int length) {
      return 0;
    }

    @Override
    public MutableDirectBuffer buffer() {
      return space;
    }

    @Override
    public void commit() {}
  }
}

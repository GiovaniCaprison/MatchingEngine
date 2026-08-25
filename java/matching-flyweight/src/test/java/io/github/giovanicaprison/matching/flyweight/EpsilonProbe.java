package io.github.giovanicaprison.matching.flyweight;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.naive.NaiveEngineFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The body of the allocation proof: a whole log through one engine, in a JVM whose collector never
 * collects (NFR-4.3).
 *
 * <p>Run under Epsilon with a heap sized to the setup and little more. An engine that allocates
 * nothing in steady state runs to completion and exits cleanly; one that allocates per command
 * exhausts the heap and dies, which is what the control arm demonstrates. Events go into a fixed
 * buffer and are dropped, because the proof is about the engine's memory.
 *
 * <p>The log is indexed in place over the file's own bytes rather than read through the general
 * loader, the lean-pooled probe's lesson: the loader copies the log twice and the copies would
 * dominate a budget that should price the engine, whose ladder and slab are the setup being priced
 * here.
 */
public final class EpsilonProbe {

  private EpsilonProbe() {}

  public static void main(final String[] arguments) throws Exception {
    final byte[] bytes = Files.readAllBytes(Path.of(arguments[0]));
    final ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    in.position(8);
    final int count = in.getInt();
    in.getInt();
    final int[] offsets = new int[count];
    final int[] lengths = new int[count];
    int at = in.position();
    for (int command = 0; command < count; command++) {
      lengths[command] = in.getInt(at);
      offsets[command] = at + Integer.BYTES;
      at = offsets[command] + lengths[command];
    }
    final UnsafeBuffer buffer = new UnsafeBuffer(bytes);
    final Sink sink = new Sink();
    final MatchingEngine engine =
        arguments[1].equals("flyweight")
            ? new FlyweightEngineFactory().create(sink)
            : new NaiveEngineFactory().create(sink);
    for (int command = 0; command < count; command++) {
      engine.onCommand(buffer, offsets[command], lengths[command]);
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

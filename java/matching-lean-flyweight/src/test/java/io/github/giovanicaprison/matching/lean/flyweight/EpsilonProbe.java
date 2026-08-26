package io.github.giovanicaprison.matching.lean.flyweight;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The body of the lean twin's allocation proof: a whole log through one engine, in a JVM whose
 * collector never collects (NFR-4.3).
 *
 * <p>The control arm is the lean-naive engine, so the two arms differ only by the rung's mechanism:
 * same remit, same flow, one allocates per command and dies, one does not and exits.
 *
 * <p>The log is indexed in place over the file's own bytes rather than read through the general
 * loader, because the loader copies the log twice and the copies would dominate the heap budget.
 * The budget should price the engine, not the harness.
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
        arguments[1].equals("lean-flyweight")
            ? new LeanEngineFactory().create(sink)
            : new io.github.giovanicaprison.matching.lean.naive.LeanEngineFactory().create(sink);
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

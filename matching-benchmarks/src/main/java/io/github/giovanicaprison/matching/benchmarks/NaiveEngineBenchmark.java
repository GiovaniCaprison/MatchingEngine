package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.api.Instrument;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.naive.NaiveMatchingEngineFactory;
import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import io.github.giovanicaprison.matching.protocol.NewOrderDecoder;
import java.util.concurrent.TimeUnit;
import org.agrona.DirectBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Rung 0, by command type, across book sizes.
 *
 * <p>Single shot mode with fresh state per iteration, because the engine is a state machine and
 * every command changes what the next one costs. Average time over a batch that mutates the book
 * would report the average of a moving target, and per invocation state rebuilding on a batch this
 * small would be all fixture and no measurement. Each iteration warms a book to {@code bookSize},
 * then replays a fixed batch and reports the time per command.
 *
 * <p>The batch does perturb the book: it is the same size as the smallest book measured, and a
 * twentieth of the largest. That is the price of resetting per iteration rather than per command
 * and it is why the numbers are a curve rather than a point.
 *
 * <p>Decode is measured on its own as well as inside the engine, because decode is part of an
 * implementation and part of its cost (NFR-5.6). Without that split, the difference between two
 * books can be swamped by the difference between two decoders.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(
    value = 1,
    jvmArgs = {"--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"})
public class NaiveEngineBenchmark {

  /** Commands per measured batch. Constant, because an annotation cannot read a parameter. */
  private static final int BATCH = 2_000;

  private static final Instrument INSTRUMENT = new Instrument(1, 5L, 1L, 1L, 1_000_000_000L, 4);

  @Param({"2000", "8000", "32000"})
  public int bookSize;

  private final FlowParameters flow = FlowParameters.standard(42L);
  private final NaiveMatchingEngineFactory factory = new NaiveMatchingEngineFactory();

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final NewOrderDecoder newOrder = new NewOrderDecoder();

  private CommandLog warmUp;
  private CommandLog passive;
  private CommandLog crossing;
  private CommandLog cancels;

  private CountingEventSink sink;
  private MatchingEngine engine;

  @Setup(Level.Trial)
  public void buildLogs() {
    final LogGenerator generator = new LogGenerator(flow);
    warmUp = generator.passiveOrders(bookSize, 1L);
    passive = generator.passiveOrders(BATCH, bookSize + 1L);
    crossing = generator.crossingOrders(BATCH, bookSize + 1L);
    cancels = generator.cancels(BATCH, 1L, bookSize + 1L);
  }

  @Setup(Level.Iteration)
  public void warmBook() {
    sink = new CountingEventSink();
    engine = factory.create(INSTRUMENT, sink);
    replay(warmUp);
  }

  @Benchmark
  @OperationsPerInvocation(BATCH)
  public long submitResting() {
    replay(passive);
    return sink.checksum;
  }

  @Benchmark
  @OperationsPerInvocation(BATCH)
  public long submitCrossing() {
    replay(crossing);
    return sink.checksum;
  }

  @Benchmark
  @OperationsPerInvocation(BATCH)
  public long cancel() {
    replay(cancels);
    return sink.checksum;
  }

  /**
   * Reading every field of every command without touching the engine.
   *
   * <p>This is the floor the other three sit on. Whatever a book costs, it costs that on top of
   * this.
   */
  @Benchmark
  @OperationsPerInvocation(BATCH)
  public void decodeOnly(final Blackhole blackhole) {
    final DirectBuffer buffer = passive.buffer();
    for (int i = 0; i < passive.count(); i++) {
      header.wrap(buffer, passive.offset(i));
      newOrder.wrap(
          buffer,
          passive.offset(i) + header.encodedLength(),
          header.blockLength(),
          header.version());
      blackhole.consume(newOrder.frame().sequence());
      blackhole.consume(newOrder.clientOrderId());
      blackhole.consume(newOrder.participantId());
      blackhole.consume(newOrder.side());
      blackhole.consume(newOrder.pricing());
      blackhole.consume(newOrder.timeInForce());
      blackhole.consume(newOrder.flags().postOnly());
      blackhole.consume(newOrder.price());
      blackhole.consume(newOrder.quantity());
    }
  }

  private void replay(final CommandLog log) {
    final DirectBuffer buffer = log.buffer();
    for (int i = 0; i < log.count(); i++) {
      engine.onCommand(buffer, log.offset(i), log.length(i));
    }
  }
}

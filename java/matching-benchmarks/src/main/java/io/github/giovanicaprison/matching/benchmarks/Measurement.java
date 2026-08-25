package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.api.MatchingEngineFactory;
import io.github.giovanicaprison.matching.flow.CommandLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;

/**
 * One run: a driver offering commands at a fixed rate, an engine applying them, and a verifier
 * checking what came out.
 *
 * <p>Three threads because a measurement of one thread's work cannot have anything else on that
 * thread. The driver's copy into the ring is a gateway's job, the verifier's counting and hashing
 * is a consumer's, and neither belongs in the number.
 *
 * <p>Open loop. The driver publishes at the moment it intended to, whether or not the engine has
 * kept up, so a stall shows up as a queue rather than as samples nobody took. A harness that waited
 * for the previous command would report a tail that does not exist.
 *
 * <p>Each thread pins itself before it does anything else, and the pin is read back rather than
 * assumed. Where a platform has no such call every pin reads as unavailable and the run says so.
 */
public final class Measurement {

  private final CommandLog log;
  private final MeasurementParameters parameters;
  private final OneToOneRingBuffer commands;
  private final OneToOneRingBuffer events;
  private final RingPublisher publisher;
  private final Timings timings;
  private final VerificationRecord verification = new VerificationRecord();
  private final UnsafeBuffer nothing = new UnsafeBuffer(new byte[0]);

  private final List<Setting> placement = new ArrayList<>();

  // Written by one thread at a time: the main thread before the others start and after they are
  // joined, the engine's thread in between. The joins order every write before the read.
  private final List<Setting> sampledBefore = new ArrayList<>();
  private final List<Setting> sampledAfter = new ArrayList<>();

  private static final Path MACHINE = Path.of("/");

  private volatile boolean ended;
  private Map<Counter, Long> counted = Map.of();
  private boolean countersMultiplexed;
  private long publishRetries;
  private long commandsQueued;
  private long eventsQueued;
  private int applied;

  private Measurement(final CommandLog log, final MeasurementParameters parameters) {
    this.log = log;
    this.parameters = parameters;
    this.commands = Rings.of(parameters.inputRing());
    this.events = Rings.of(parameters.outputRing());
    this.publisher = new RingPublisher(events);
    this.timings = new Timings(log.count() - log.measuredFrom(), parameters.compilationWarmup());
  }

  public static Outcome run(
      final CommandLog log,
      final MatchingEngineFactory factory,
      final MeasurementParameters parameters) {
    final Measurement measurement = new Measurement(log, parameters);
    return measurement.execute(factory);
  }

  private Outcome execute(final MatchingEngineFactory factory) {
    final MatchingEngine engine = factory.create(publisher);
    final MeasurementParameters.Cores cores = parameters.cores();
    sampledBefore.addAll(Sample.ofCore(MACHINE, cores.engine()));
    final Thread verifier =
        thread("verifier", () -> pinned("verifier", cores.verifier(), this::verify));
    final Thread runner =
        thread("engine", () -> pinned("engine", cores.engine(), () -> apply(engine)));
    verifier.start();
    runner.start();
    pinned("driver", cores.driver(), this::drive);
    join(runner);
    join(verifier);
    sampledAfter.addAll(Sample.ofCore(MACHINE, cores.engine()));
    return outcome();
  }

  /**
   * The driver. Offers every command at the moment its rate says to, and never waits for the
   * engine.
   */
  private void drive() {
    final long period = parameters.periodNanos();
    final long begin = System.nanoTime();
    final int measuredFrom = log.measuredFrom();
    for (int command = 0; command < log.count(); command++) {
      final long intendedAt = begin + command * period;
      while (System.nanoTime() < intendedAt) {
        Thread.onSpinWait();
      }
      final int measured = command - measuredFrom;
      if (measured >= 0) {
        timings.intended(measured, intendedAt);
      }
      publish(command);
      if (measured >= 0) {
        timings.published(measured, System.nanoTime());
      }
      commandsQueued = Math.max(commandsQueued, queued(commands));
    }
  }

  /**
   * Pins the thread, records what happened, and gets on with the work.
   *
   * <p>A refused pin does not stop a run. It changes what the run is worth, which is what the
   * record is for.
   */
  private void pinned(final String name, final int core, final Runnable work) {
    if (core != MeasurementParameters.UNPINNED) {
      final Setting setting = Affinity.pin(name, core);
      synchronized (placement) {
        placement.add(setting);
      }
    }
    work.run();
  }

  private void publish(final int command) {
    while (!commands.write(Rings.COMMAND, log.buffer(), log.offset(command), log.length(command))) {
      publishRetries++;
      Thread.onSpinWait();
    }
  }

  /**
   * The engine's thread. One message per read, so each command is timed on its own rather than
   * amortised over a batch the harness chose.
   *
   * <p>Split into two phases so that the counters bracket the region that gets reported, and so
   * that nothing decides per command which phase it is in.
   */
  private void apply(final MatchingEngine engine) {
    // The thread's own switch counts, read here because they belong to this thread and by the time
    // anything else could look the thread is gone. Well before the measured region either way.
    sampledBefore.addAll(Sample.ofThisThread(MACHINE));
    final int measuredFrom = log.measuredFrom();
    final MessageHandler handler =
        (type, buffer, index, length) -> {
          final int measured = applied - measuredFrom;
          final long from = System.nanoTime();
          engine.onCommand(buffer, index, length);
          final long to = System.nanoTime();
          if (measured >= 0) {
            timings.record(measured, from, to);
          }
          applied++;
        };

    final int reportFrom = Math.min(log.count(), measuredFrom + parameters.compilationWarmup());
    applyUntil(handler, reportFrom);
    count(handler);
    sampledAfter.addAll(Sample.ofThisThread(MACHINE));

    while (!events.write(Rings.END, nothing, 0, 0)) {
      Thread.onSpinWait();
    }
  }

  /**
   * The reported region, with the counters open across it and nothing else.
   *
   * <p>Opened here rather than at startup because counting is per thread, and this is the thread.
   * Compiler threads, collector threads and everything before the runtime settled are on other
   * threads and are not counted, which is the whole reason to do this in process instead of running
   * the process under a profiler.
   */
  private void count(final MessageHandler handler) {
    final Optional<Counters> counters = Counters.open(parameters.counters());
    final Optional<Counters.Reading> before = counters.map(Counters::read);
    applyUntil(handler, log.count());
    counters.ifPresent(
        open -> {
          final Counters.Reading after = open.read();
          counted = after.since(before.orElseThrow());
          countersMultiplexed = after.multiplexed();
          open.close();
        });
  }

  private void applyUntil(final MessageHandler handler, final int bound) {
    while (applied < bound) {
      if (commands.read(handler, 1) == 0) {
        Thread.onSpinWait();
      }
    }
  }

  /** The verifier's thread. Every byte the engine produced is hashed here and nowhere else. */
  private void verify() {
    final MessageHandler handler =
        (type, buffer, index, length) -> {
          if (type == Rings.END) {
            ended = true;
          } else {
            verification.record(buffer, index, length);
          }
        };
    while (!ended) {
      if (events.read(handler, 64) == 0) {
        Thread.onSpinWait();
      }
      eventsQueued = Math.max(eventsQueued, queued(events));
    }
  }

  private Outcome outcome() {
    return new Outcome(
        applied,
        timings,
        verification,
        List.copyOf(placement),
        List.copyOf(sampledBefore),
        List.copyOf(sampledAfter),
        counted,
        countersMultiplexed,
        publishRetries,
        publisher.waits(),
        publisher.waitedNanos(),
        commandsQueued,
        eventsQueued);
  }

  private static long queued(final OneToOneRingBuffer ring) {
    return ring.producerPosition() - ring.consumerPosition();
  }

  private static Thread thread(final String name, final Runnable work) {
    final Thread thread = new Thread(work, name);
    thread.setDaemon(true);
    return thread;
  }

  private static void join(final Thread thread) {
    try {
      thread.join();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for " + thread.getName(), e);
    }
  }

  /**
   * What a run produced.
   *
   * @param commands how many were applied
   * @param timings every command's four timestamps
   * @param verification what the engine emitted, counted and hashed
   * @param placement where each thread ended up, as read back from the kernel
   * @param sampledBefore the machine's transient state around the measured core before the run
   * @param sampledAfter the same readings afterwards, so the pair says what moved during the run
   * @param counted what the hardware counted over the reported region, empty where it could not
   * @param countersMultiplexed whether the processor had to share counter slots, which makes every
   *     value above an extrapolation rather than a count
   * @param publishRetries times the driver found the input ring full
   * @param publisherWaits times the engine found the output ring full
   * @param publisherWaitedNanos how long those waits cost in total
   * @param commandsQueuedHighWater the most bytes ever waiting on the input ring
   * @param eventsQueuedHighWater the most bytes ever waiting on the output ring
   */
  public record Outcome(
      int commands,
      Timings timings,
      VerificationRecord verification,
      List<Setting> placement,
      List<Setting> sampledBefore,
      List<Setting> sampledAfter,
      Map<Counter, Long> counted,
      boolean countersMultiplexed,
      long publishRetries,
      long publisherWaits,
      long publisherWaitedNanos,
      long commandsQueuedHighWater,
      long eventsQueuedHighWater) {

    /**
     * Whether the harness kept up with itself.
     *
     * <p>A run that stalled at either end describes the harness rather than the engine, so it says
     * so instead of being averaged into a result.
     */
    public boolean harnessKeptUp() {
      return publishRetries == 0 && publisherWaits == 0;
    }

    /** Whether every thread ended up where it was asked to be. */
    public boolean placedAsAsked() {
      return placement.stream().allMatch(Setting::satisfied);
    }

    /** Everything a run produced, in the directory it produced it in. */
    public void writeTo(final Run run) {
      write(run.file("measurement.json"), toJson());
      verification.writeTo(run.file("verification.json"));
      timings.writeHistograms(run.file("latency.hdr"));
      timings.writeTimings(run.file("timings.bin"));
    }

    private static void write(final Path file, final String content) {
      try {
        Files.writeString(file, content);
      } catch (final IOException e) {
        throw new UncheckedIOException("cannot write " + file, e);
      }
    }

    public String toJson() {
      final Json json =
          new Json()
              .object()
              .field("commands", commands)
              .field("harnessKeptUp", harnessKeptUp())
              .field("placedAsAsked", placedAsAsked())
              .field("publishRetries", publishRetries)
              .field("publisherWaits", publisherWaits)
              .field("publisherWaitedNanos", publisherWaitedNanos)
              .field("commandsQueuedHighWater", commandsQueuedHighWater)
              .field("eventsQueuedHighWater", eventsQueuedHighWater)
              .field("events", verification.events())
              .field("digest", Long.toHexString(verification.digest()));
      json.object("counts");
      verification.countsByName().forEach(json::field);
      json.end();
      json.object("counters").field("multiplexed", countersMultiplexed);
      counted.forEach((counter, value) -> json.field(counter.name(), value));
      json.end();
      json.array("placement");
      for (final Setting setting : placement) {
        json.object()
            .field("name", setting.name())
            .field("expected", setting.expected())
            .field("actual", setting.actual())
            .field("status", setting.status().name())
            .end();
      }
      json.end();
      samples(json, "sampledBefore", sampledBefore);
      samples(json, "sampledAfter", sampledAfter);
      summary(json, "service", timings.service());
      summary(json, "response", timings.response());
      return json.end().toString();
    }

    /** A sample's settings carry no expectation, so only what was found is written. */
    private static void samples(final Json json, final String name, final List<Setting> values) {
      json.array(name);
      for (final Setting setting : values) {
        json.object()
            .field("name", setting.name())
            .field("source", setting.source())
            .field("actual", setting.actual())
            .field("status", setting.status().name())
            .end();
      }
      json.end();
    }

    /**
     * A few percentiles for reading at a glance. The encoded histogram is the artifact: a stored
     * percentile answers one question forever.
     */
    private static void summary(final Json json, final String name, final Histogram histogram) {
      json.object(name)
          .field("count", histogram.getTotalCount())
          .field("p50", histogram.getValueAtPercentile(50))
          .field("p99", histogram.getValueAtPercentile(99))
          .field("p999", histogram.getValueAtPercentile(99.9))
          .field("max", histogram.getMaxValue())
          .end();
    }
  }
}

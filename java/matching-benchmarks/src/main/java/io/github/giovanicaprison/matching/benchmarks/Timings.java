package io.github.giovanicaprison.matching.benchmarks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;

/**
 * Every command's start and finish, kept rather than summarised.
 *
 * <p>Two stores into a preallocated array is cheaper on the measured core than recording into a
 * histogram, which computes a bucket index and then touches a counts array large enough to miss
 * cache. It is also strictly more informative: a histogram cannot say when a stall happened,
 * whether stalls cluster, or what the run looked like as it went. The histogram is produced from
 * this afterwards, off the measured core, and stored encoded so it can still be merged and
 * re-quantiled.
 *
 * <p>Four timestamps a command, so every wait is attributable rather than lumped together: the
 * driver being late to offer it, the time it sat on the ring, and the engine's own work. A number
 * nobody can decompose is a number somebody will argue about.
 *
 * <p>Recording is the driver's business and reading is the caller's, which is why only the
 * accessors are public.
 *
 * <p>The arrays are sized up front. A run too large to hold its own timings is a run whose
 * parameters are wrong, and it fails here rather than quietly recording less than it claims.
 */
public final class Timings {

  private static final byte[] MAGIC = "METIMES1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  /**
   * Ten seconds, in nanoseconds. Anything slower than this is a defect rather than a measurement.
   */
  private static final long LONGEST = 10_000_000_000L;

  private final long[] intended;
  private final long[] published;
  private final long[] started;
  private final long[] finished;
  private final int capacity;
  private final int reportFrom;

  private int recorded;

  /**
   * @param capacity how many commands will be timed
   * @param reportFrom the first command a histogram includes, which is where compilation has
   *     settled
   */
  Timings(final int capacity, final int reportFrom) {
    this.capacity = capacity;
    this.reportFrom = reportFrom;
    this.intended = new long[capacity];
    this.published = new long[capacity];
    this.started = new long[capacity];
    this.finished = new long[capacity];
  }

  /**
   * Called by the driver before it publishes, so the engine sees it through the ring's own edge.
   */
  void intended(final int command, final long at) {
    intended[command] = at;
  }

  public long intended(final int command) {
    return intended[command];
  }

  /** Called by the driver once the command is on the ring. */
  void published(final int command, final long at) {
    published[command] = at;
  }

  /** Called by the engine, on the measured core. Nothing else here is. */
  void record(final int command, final long from, final long to) {
    started[command] = from;
    finished[command] = to;
    recorded++;
  }

  public int recorded() {
    return recorded;
  }

  public int capacity() {
    return capacity;
  }

  /** How long the engine took, which is the service time. */
  public Histogram service() {
    return histogram(command -> finished[command] - started[command]);
  }

  /** How late the driver was in offering the command, which is harness cost and not a result. */
  public Histogram offered() {
    return histogram(command -> published[command] - intended[command]);
  }

  /** How long the command sat on the ring before the engine reached it. */
  public Histogram queued() {
    return histogram(command -> started[command] - published[command]);
  }

  /**
   * How long a command took from the moment it was meant to arrive, which is the number a client
   * sees.
   *
   * <p>The difference between the two is queueing, and it is the whole reason the driver is open
   * loop. A harness that sent the next command when the last one returned would never take these
   * samples.
   */
  public Histogram response() {
    return histogram(command -> finished[command] - intended[command]);
  }

  void writeHistograms(final Path file) {
    try (var out = new java.io.PrintStream(Files.newOutputStream(file))) {
      final HistogramLogWriter writer = new HistogramLogWriter(out);
      writer.outputLogFormatVersion();
      writer.outputStartTime(System.currentTimeMillis());
      writer.outputLegend();
      final Histogram service = service();
      service.setTag("service");
      writer.outputIntervalHistogram(service);
      final Histogram response = response();
      response.setTag("response");
      writer.outputIntervalHistogram(response);
      final Histogram offered = offered();
      offered.setTag("offered");
      writer.outputIntervalHistogram(offered);
      final Histogram queued = queued();
      queued.setTag("queued");
      writer.outputIntervalHistogram(queued);
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot write the histograms to " + file, e);
    }
  }

  /**
   * The three timestamps per command, so a run can be looked at as a series and not only a shape.
   */
  void writeTimings(final Path file) {
    final ByteBuffer out =
        ByteBuffer.allocate(MAGIC.length + Integer.BYTES + recorded * 4 * Long.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    out.put(MAGIC).putInt(recorded);
    for (int command = 0; command < recorded; command++) {
      out.putLong(intended[command])
          .putLong(published[command])
          .putLong(started[command])
          .putLong(finished[command]);
    }
    try {
      Files.write(file, out.array());
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot write the timings to " + file, e);
    }
  }

  private Histogram histogram(final Duration duration) {
    final Histogram histogram = new Histogram(1, LONGEST, 3);
    for (int command = reportFrom; command < recorded; command++) {
      histogram.recordValue(Math.max(1, Math.min(LONGEST, duration.of(command))));
    }
    return histogram;
  }

  private interface Duration {
    long of(int command);
  }
}

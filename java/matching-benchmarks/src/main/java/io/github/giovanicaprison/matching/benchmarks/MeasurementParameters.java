package io.github.giovanicaprison.matching.benchmarks;

import org.agrona.BitUtil;

/**
 * How a run is driven.
 *
 * <p>A rate rather than a repetition count, because the question an operator asks is what the tail
 * looks like at a given offered load. The rate is held whatever the engine does, and where it
 * cannot be held that is the result.
 *
 * @param ratePerSecond commands offered every second, held open loop
 * @param compilationWarmup commands applied and timed but left out of the histograms, while the
 *     runtime settles on the code it is going to run
 * @param inputRing capacity in bytes of the queue between the driver and the engine
 * @param outputRing capacity in bytes of the queue between the engine and the verifier
 * @param cores which core each of the three threads runs on
 */
public record MeasurementParameters(
    long ratePerSecond, int compilationWarmup, int inputRing, int outputRing, Cores cores) {

  /** No core in particular. */
  public static final int UNPINNED = -1;

  /**
   * Which core each thread runs on.
   *
   * <p>The engine's is the one that matters, and it wants a core the kernel was told to leave
   * alone. The other two only need to be somewhere else.
   *
   * @param driver the thread offering commands
   * @param engine the thread being measured
   * @param verifier the thread counting and hashing what came out
   */
  public record Cores(int driver, int engine, int verifier) {

    /** A machine nobody controlled, where a run is exploratory whatever else it does. */
    public static Cores anywhere() {
      return new Cores(UNPINNED, UNPINNED, UNPINNED);
    }
  }

  public MeasurementParameters {
    if (ratePerSecond <= 0) {
      throw new IllegalArgumentException("a rate has to be positive: " + ratePerSecond);
    }
    if (compilationWarmup < 0) {
      throw new IllegalArgumentException("a warm-up cannot be negative: " + compilationWarmup);
    }
    if (!BitUtil.isPowerOfTwo(inputRing) || !BitUtil.isPowerOfTwo(outputRing)) {
      throw new IllegalArgumentException("a ring's capacity is a power of two");
    }
  }

  /**
   * A rate, with rings large enough that a short stall is absorbed rather than reported.
   *
   * <p>Sixteen megabytes is around two hundred thousand commands, so a millisecond of
   * unresponsiveness at a million a second costs occupancy and not a stall. Anything longer than
   * that is worth knowing about.
   */
  public static MeasurementParameters at(final long ratePerSecond, final Cores cores) {
    return new MeasurementParameters(ratePerSecond, 2_000_000, 1 << 24, 1 << 24, cores);
  }

  public long periodNanos() {
    return 1_000_000_000L / ratePerSecond;
  }
}

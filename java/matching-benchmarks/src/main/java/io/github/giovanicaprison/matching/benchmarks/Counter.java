package io.github.giovanicaprison.matching.benchmarks;

import java.util.EnumSet;
import java.util.Set;

/**
 * A hardware counter, named by what it means rather than by its encoding.
 *
 * <p>The first eight are what the kernel calls generic hardware events, which every supported
 * microarchitecture maps to something. The last two go through the cache event encoding, since
 * there is no generic name for a level or a translation buffer.
 *
 * <p>A processor has a handful of counter slots. Ask for more than it has and the kernel
 * multiplexes them, scaling every value by the fraction of time each was actually counting, so a
 * set is chosen to fit and a run that multiplexed anyway says so.
 */
public enum Counter {
  CYCLES(Type.HARDWARE, 0),
  INSTRUCTIONS(Type.HARDWARE, 1),
  CACHE_REFERENCES(Type.HARDWARE, 2),
  CACHE_MISSES(Type.HARDWARE, 3),
  BRANCH_INSTRUCTIONS(Type.HARDWARE, 4),
  BRANCH_MISSES(Type.HARDWARE, 5),
  STALLED_CYCLES_FRONTEND(Type.HARDWARE, 7),
  STALLED_CYCLES_BACKEND(Type.HARDWARE, 8),
  L1D_READ_MISSES(Type.HW_CACHE, cache(0, 0, 1)),
  DTLB_READ_MISSES(Type.HW_CACHE, cache(3, 0, 1));

  /** What the kernel calls the family an event belongs to. */
  enum Type {
    HARDWARE(0),
    HW_CACHE(3);

    private final int id;

    Type(final int id) {
      this.id = id;
    }

    int id() {
      return id;
    }
  }

  private final Type type;
  private final long config;

  Counter(final Type type, final long config) {
    this.type = type;
    this.config = config;
  }

  Type type() {
    return type;
  }

  long config() {
    return config;
  }

  /**
   * A set small enough to fit any processor's counter slots.
   *
   * <p>Cycles and instructions sit in fixed counters on Intel and AMD alike, so these four need two
   * general purpose slots and every processor worth measuring on has four. The full set fits an Ice
   * Lake server with hyperthreading off, and a run reports it if the kernel had to multiplex.
   */
  public static Set<Counter> few() {
    return EnumSet.of(CYCLES, INSTRUCTIONS, CACHE_MISSES, BRANCH_MISSES);
  }

  /** The cache event encoding: which cache, which operation, and hit or miss. */
  private static long cache(final int level, final int operation, final int result) {
    return level | (long) operation << 8 | (long) result << 16;
  }
}

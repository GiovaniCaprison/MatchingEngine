package io.github.giovanicaprison.matching.benchmarks;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Puts a thread on one core and checks that it stayed there.
 *
 * <p>A thread the scheduler is free to move is a thread whose cache is somebody else's, and the
 * cost arrives as tail latency rather than as an average. Pinning is what makes the three threads
 * of a run independent measurements instead of one contended one.
 *
 * <p>Straight to {@code sched_setaffinity} through the foreign function API. The alternative is a
 * library and its transitive native binding, for two calls that the platform has had since it had
 * threads.
 *
 * <p>The call pins whichever thread makes it, so each thread pins itself before it does anything
 * else. Platform threads only: a virtual thread's carrier is not its own to place.
 *
 * <p>Only Linux has this. Everywhere else the symbol is absent, every pin reads as unavailable, and
 * a run stays exploratory, which is the honest outcome on a laptop.
 */
final class Affinity {

  /** glibc's {@code cpu_set_t} is a fixed 1024 bit mask. */
  private static final int MASK_BYTES = 128;

  private static final Optional<MethodHandle> SET = downcall("sched_setaffinity");
  private static final Optional<MethodHandle> GET = downcall("sched_getaffinity");

  private Affinity() {}

  static boolean available() {
    return SET.isPresent() && GET.isPresent();
  }

  /**
   * Pins the calling thread and reads the mask back.
   *
   * <p>Reading it back is the point. A call that returned success and a thread that is still free
   * to move would be the worst of the three outcomes, because the run would look controlled.
   *
   * @param name what this thread is called in a manifest
   * @param core the core to pin to
   */
  static Setting pin(final String name, final int core) {
    final String setting = name + " core";
    if (core < 0 || core >= MASK_BYTES * 8) {
      return Setting.required(setting, "cpu_set_t", String.valueOf(core), "out of range");
    }
    if (!available()) {
      return Setting.required(setting, "sched_setaffinity", String.valueOf(core), null);
    }
    try (Arena arena = Arena.ofConfined()) {
      final MemorySegment mask = arena.allocate(MASK_BYTES);
      mask.fill((byte) 0);
      set(mask, core);
      if ((int) SET.orElseThrow().invokeExact(0, (long) MASK_BYTES, mask) != 0) {
        return Setting.required(setting, "sched_setaffinity", String.valueOf(core), "refused");
      }
      mask.fill((byte) 0);
      if ((int) GET.orElseThrow().invokeExact(0, (long) MASK_BYTES, mask) != 0) {
        return Setting.required(setting, "sched_getaffinity", String.valueOf(core), "unreadable");
      }
      return Setting.required(setting, "sched_getaffinity", String.valueOf(core), only(mask));
    } catch (final Throwable e) {
      return Setting.required(setting, "sched_setaffinity", String.valueOf(core), "failed: " + e);
    }
  }

  /** The core a mask allows, or the whole mask when it allows more than one. */
  private static String only(final MemorySegment mask) {
    int found = -1;
    int count = 0;
    for (int core = 0; core < MASK_BYTES * 8; core++) {
      if (isSet(mask, core)) {
        count++;
        found = core;
      }
    }
    if (count == 1) {
      return String.valueOf(found);
    }
    return count + " cores";
  }

  private static void set(final MemorySegment mask, final int core) {
    final int at = core / 8;
    mask.set(
        ValueLayout.JAVA_BYTE, at, (byte) (mask.get(ValueLayout.JAVA_BYTE, at) | 1 << core % 8));
  }

  private static boolean isSet(final MemorySegment mask, final int core) {
    return (mask.get(ValueLayout.JAVA_BYTE, core / 8) & 1 << core % 8) != 0;
  }

  private static Optional<MethodHandle> downcall(final String symbol) {
    final Linker linker = Linker.nativeLinker();
    return linker
        .defaultLookup()
        .find(symbol)
        .map(
            address ->
                linker.downcallHandle(
                    address,
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS)));
  }
}

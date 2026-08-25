package io.github.giovanicaprison.matching.benchmarks;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Hardware counters for the thread that opened them, read around a region rather than sampled.
 *
 * <p>This is what fills the gap between the two languages. Instruction counts from a simulator are
 * C++ only, because a simulator cannot follow a runtime that rewrites its own code, so without this
 * a Java implementation could only be compared to itself. Counters opened on the measured thread
 * and read at both ends of the region give both languages the same number, computed the same way.
 *
 * <p>What that buys is the quietest comparison available. Instructions retired varies run to run
 * only with recompilation and safepoints, where cycles move with frequency, neighbours and luck.
 * Two implementations of one algorithm differ in instructions by an amount that means something.
 *
 * <p>Counting is per thread and excludes the kernel and the hypervisor, so a syscall somebody else
 * made cannot land in the number. That also means it is user space work being compared, which is
 * what the engine is.
 *
 * <p>The read is a {@code read} syscall, not the mapped page and {@code rdpmc}. Emitting that
 * instruction is not something the foreign function API can do, and it would only be available on
 * one side of the comparison anyway. Bracketing a region of millions of commands makes the cost of
 * two syscalls irrelevant, which is why the region rather than the command is the unit here.
 *
 * <p>Linux only, and only a Linux whose kernel grants the call. Elsewhere every counter is
 * unavailable and a run says so.
 */
public final class Counters implements AutoCloseable {

  /**
   * {@code perf_event_open} has no libc wrapper, so it goes through {@code syscall} by number.
   *
   * <p>A number, which means the platform has to be checked and not just the symbol. Every system
   * has {@code syscall}, and the same number somewhere else is a different call into a different
   * kernel, so an absent symbol is not the guard here: the operating system is.
   */
  private static final Map<String, Integer> SYSCALL_NUMBERS =
      Map.of("amd64", 298, "x86_64", 298, "aarch64", 241);

  private static final int ATTR_BYTES = 128;
  private static final int EXCLUDE_KERNEL_AND_HYPERVISOR = 0x60;
  private static final int TOTAL_TIME_ENABLED_AND_RUNNING = 3;
  private static final int READING_BYTES = 24;
  private static final int SELF = 0;
  private static final int ANY_CPU = -1;
  private static final int NO_GROUP = -1;

  private static final MemoryLayout ERRNO = Linker.Option.captureStateLayout();
  private static final VarHandle ERRNO_FIELD =
      ERRNO.varHandle(MemoryLayout.PathElement.groupElement("errno"));

  private static final boolean LINUX = "Linux".equals(System.getProperty("os.name"));
  private static final Optional<Integer> SYSCALL_NUMBER =
      LINUX
          ? Optional.ofNullable(SYSCALL_NUMBERS.get(System.getProperty("os.arch")))
          : Optional.empty();
  private static final Optional<MethodHandle> SYSCALL = syscall();
  private static final Optional<MethodHandle> READ = readDowncall();
  private static final Optional<MethodHandle> CLOSE = closeDowncall();
  private static final boolean OPENS = probe();

  private final Map<Counter, Integer> descriptors;
  private final Arena arena;
  private final MemorySegment buffer;

  private Counters(final Map<Counter, Integer> descriptors, final Arena arena) {
    this.descriptors = descriptors;
    this.arena = arena;
    this.buffer = arena.allocate(READING_BYTES);
  }

  /**
   * Whether counters can actually be opened, learned by opening one rather than by finding symbols.
   * Every symbol here exists on machines that still refuse the call: a hardened kernel, a
   * container's seccomp filter, or {@code perf_event_paranoid} above what the instruments need all
   * leave the syscall in place and the answer no. The first shared runner this ran on was exactly
   * that machine.
   */
  public static boolean available() {
    return OPENS;
  }

  private static boolean probe() {
    if (SYSCALL.isEmpty() || READ.isEmpty() || CLOSE.isEmpty() || SYSCALL_NUMBER.isEmpty()) {
      return false;
    }
    try (Arena arena = Arena.ofConfined()) {
      final int descriptor = perfEventOpen(arena, Counter.INSTRUCTIONS);
      if (descriptor < 0) {
        return false;
      }
      closeDescriptor(descriptor);
      return true;
    }
  }

  /**
   * Opens a counter set for the calling thread.
   *
   * <p>Returns empty when the platform cannot do it or the kernel refuses. A refusal is normally
   * {@code perf_event_paranoid} being stricter than the instruments need, which the environment
   * report names.
   */
  public static Optional<Counters> open(final Set<Counter> wanted) {
    if (!available() || wanted.isEmpty()) {
      return Optional.empty();
    }
    final Arena arena = Arena.ofShared();
    final Map<Counter, Integer> descriptors = new EnumMap<>(Counter.class);
    for (final Counter counter : wanted) {
      final int descriptor = perfEventOpen(arena, counter);
      if (descriptor < 0) {
        descriptors.values().forEach(Counters::closeDescriptor);
        arena.close();
        return Optional.empty();
      }
      descriptors.put(counter, descriptor);
    }
    return Optional.of(new Counters(descriptors, arena));
  }

  /** Every counter as it stands, with the time the kernel had it running. */
  public Reading read() {
    final Map<Counter, Sample> samples = new LinkedHashMap<>();
    descriptors.forEach((counter, descriptor) -> samples.put(counter, sample(descriptor)));
    return new Reading(Map.copyOf(samples));
  }

  @Override
  public void close() {
    descriptors.values().forEach(Counters::closeDescriptor);
    descriptors.clear();
    arena.close();
  }

  private Sample sample(final int descriptor) {
    try {
      final long read =
          (long) READ.orElseThrow().invokeExact(descriptor, buffer, (long) READING_BYTES);
      if (read != READING_BYTES) {
        return Sample.UNREADABLE;
      }
      return new Sample(
          buffer.get(ValueLayout.JAVA_LONG, 0),
          buffer.get(ValueLayout.JAVA_LONG, 8),
          buffer.get(ValueLayout.JAVA_LONG, 16));
    } catch (final Throwable e) {
      return Sample.UNREADABLE;
    }
  }

  private static int perfEventOpen(final Arena arena, final Counter counter) {
    final MemorySegment attributes = arena.allocate(ATTR_BYTES);
    attributes.fill((byte) 0);
    attributes.set(ValueLayout.JAVA_INT, 0, counter.type().id());
    attributes.set(ValueLayout.JAVA_INT, 4, ATTR_BYTES);
    attributes.set(ValueLayout.JAVA_LONG, 8, counter.config());
    attributes.set(ValueLayout.JAVA_LONG, 32, TOTAL_TIME_ENABLED_AND_RUNNING);
    attributes.set(ValueLayout.JAVA_LONG, 40, EXCLUDE_KERNEL_AND_HYPERVISOR);
    try (Arena call = Arena.ofConfined()) {
      final MemorySegment state = call.allocate(ERRNO);
      final long descriptor =
          (long)
              SYSCALL
                  .orElseThrow()
                  .invokeExact(
                      state,
                      (long) SYSCALL_NUMBER.orElseThrow(),
                      attributes,
                      SELF,
                      ANY_CPU,
                      NO_GROUP,
                      0L);
      return (int) descriptor;
    } catch (final Throwable e) {
      return -1;
    }
  }

  private static void closeDescriptor(final int descriptor) {
    try {
      CLOSE.orElseThrow().invokeExact(descriptor);
    } catch (final Throwable e) {
      // A descriptor that cannot be closed is a leak in a process that is about to write its
      // artifacts and exit. Reporting it would be noise in the one place noise is not wanted.
    }
  }

  private static Optional<MethodHandle> syscall() {
    final Linker linker = Linker.nativeLinker();
    return linker
        .defaultLookup()
        .find("syscall")
        .map(
            address ->
                linker.downcallHandle(
                    address,
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG),
                    Linker.Option.captureCallState("errno"),
                    Linker.Option.firstVariadicArg(1)));
  }

  private static Optional<MethodHandle> readDowncall() {
    final Linker linker = Linker.nativeLinker();
    return linker
        .defaultLookup()
        .find("read")
        .map(
            address ->
                linker.downcallHandle(
                    address,
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG)));
  }

  private static Optional<MethodHandle> closeDowncall() {
    final Linker linker = Linker.nativeLinker();
    return linker
        .defaultLookup()
        .find("close")
        .map(
            address ->
                linker.downcallHandle(
                    address, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)));
  }

  /**
   * One counter at one moment.
   *
   * @param value what it has counted
   * @param enabled how long the kernel has had it open, in nanoseconds
   * @param running how long it was actually counting, which is less when it shared a slot
   */
  record Sample(long value, long enabled, long running) {

    static final Sample UNREADABLE = new Sample(-1, 0, 0);

    boolean readable() {
      return value >= 0;
    }

    boolean multiplexed() {
      return readable() && running < enabled;
    }
  }

  /** Every counter at one moment. */
  public record Reading(Map<Counter, Sample> samples) {

    /** What each counter counted between two readings. */
    public Map<Counter, Long> since(final Reading earlier) {
      final Map<Counter, Long> counted = new LinkedHashMap<>();
      samples.forEach(
          (counter, sample) -> {
            final Sample before = earlier.samples().get(counter);
            if (before != null && sample.readable() && before.readable()) {
              counted.put(counter, sample.value() - before.value());
            }
          });
      return counted;
    }

    /**
     * Whether the processor had to share slots between these counters.
     *
     * <p>A multiplexed value is an extrapolation from the fraction of the time it was counting, and
     * reporting one as exact would be a made up number. A run that did this asks for a smaller set.
     */
    public boolean multiplexed() {
      return samples.values().stream().anyMatch(Sample::multiplexed);
    }
  }
}

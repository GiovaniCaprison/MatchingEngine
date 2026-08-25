package io.github.giovanicaprison.matching.benchmarks;

import java.util.BitSet;

/**
 * A kernel cpu list, as written on the command line: {@code 2-15,20}.
 *
 * <p>Parsed rather than compared as text, because whether the engine's core is the isolated one is
 * the question that makes isolation worth setting. A run pinned to a core the kernel is still
 * scheduling on is a run whose tail belongs to the kernel.
 */
final class CpuList {

  private final BitSet cores = new BitSet();

  private CpuList() {}

  static CpuList parse(final String list) {
    final CpuList parsed = new CpuList();
    if (list == null || list.isBlank()) {
      return parsed;
    }
    for (final String part : list.strip().split(",")) {
      final int dash = part.indexOf('-');
      try {
        if (dash < 0) {
          parsed.cores.set(Integer.parseInt(part.strip()));
        } else {
          final int from = Integer.parseInt(part.substring(0, dash).strip());
          final int to = Integer.parseInt(part.substring(dash + 1).strip());
          parsed.cores.set(from, to + 1);
        }
      } catch (final NumberFormatException e) {
        // A list the kernel wrote that this cannot read is worth ignoring rather than throwing
        // over:
        // the setting is recorded verbatim either way, and a run is graded on what was verified.
      }
    }
    return parsed;
  }

  boolean contains(final int core) {
    return cores.get(core);
  }

  /** Whether the list is the one core and nothing else, which is what a sibling list should be. */
  boolean containsOnly(final int core) {
    return cores.get(core) && cores.cardinality() == 1;
  }

  boolean isEmpty() {
    return cores.isEmpty();
  }
}

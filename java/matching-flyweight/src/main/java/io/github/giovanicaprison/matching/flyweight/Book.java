package io.github.giovanicaprison.matching.flyweight;

import java.util.ArrayList;
import java.util.List;

/**
 * The indexed book at this rung's representation: a flat ladder per side over the slab's queues,
 * and a name index over primitives.
 *
 * <p>The instrument's definition (FR-1.1) is what licenses the ladder. Every valid price is a
 * multiple of the tick inside the static band (VR-2.2, VR-2.3), so prices map to a dense tick
 * index, the tick maps to a side's rank, and finding a level is an array read where the rung below
 * paid a tree descent. Validation happens before any price reaches here (P-5), so the mapping never
 * checks its input.
 *
 * <p>Ranks fold the two sides into one shape: the ask ladder ranks ticks ascending and the bid
 * ladder descending, so rank zero is the best price on either side, a taker's limit becomes one
 * rank bound, and crossing is a single comparison with no side branch left in the loop.
 *
 * <p>The name index maps the id a cancel carries (participant and client order id, exact) to a
 * slot. Open addressing over one interleaved array, two words per entry so a probe touches one
 * cache line where three parallel arrays would touch three, deletion by backward shift so the
 * table's occupancy is its contents and the steady state never rehashes (NFR-4.3).
 */
final class Book {

  private final Slab slab;
  private final Ladder bids;
  private final Ladder asks;

  private final long tickSize;
  private final long baseTick;
  private final int maxRank;

  /** Client order id at {@code entry << 1}; participant in the low half beside it, slot above. */
  private long[] names;

  private int nameMask;
  private int nameCount;

  Book(final Slab slab, final long tickSize, final long baseTick, final int rankCount) {
    this.slab = slab;
    this.tickSize = tickSize;
    this.baseTick = baseTick;
    this.maxRank = rankCount - 1;
    bids = new Ladder(slab, rankCount);
    asks = new Ladder(slab, rankCount);
    allocateNames(1 << 16);
  }

  // Geometry ---------------------------------------------------------------------------------

  long priceOfTick(final int tick) {
    return (tick + baseTick) * tickSize;
  }

  /** A side's view of a tick: bids rank best-first by reversing, asks are already ascending. */
  int rankOf(final int side, final int tick) {
    return side == 0 ? maxRank - tick : tick;
  }

  int tickOfRank(final int side, final int rank) {
    return side == 0 ? maxRank - rank : rank;
  }

  long priceOfRank(final int side, final int rank) {
    return priceOfTick(tickOfRank(side, rank));
  }

  /** Every rank a market order can reach, which is all of them and never {@link Ladder#EMPTY}. */
  int marketLimit() {
    return maxRank;
  }

  /** The tick of a price validation already admitted (P-5); never checked here (P-14). */
  int tickOfPrice(final long price) {
    return (int) (price / tickSize - baseTick);
  }

  /** The worst rank on the side still willing at the price: at it, or better from its own side. */
  int willingLimitRank(final int side, final long price) {
    return rankOf(side, tickOfPrice(price));
  }

  private Ladder ladder(final int side) {
    return side == 0 ? bids : asks;
  }

  // The venue's questions ----------------------------------------------------------------------

  /**
   * The order a taker reaches next: the front of the best crossing level (FR-3.1, FR-3.3), where
   * the crossing test is one comparison because the limit arrived as a rank in the resting side's
   * own space.
   */
  int nextToTake(final int takerSide, final int limitRank) {
    final Ladder resting = ladder(takerSide ^ 1);
    final int best = resting.best();
    return best > limitRank ? 0 : resting.headAt(best);
  }

  /** The best crossing rank itself, for the walk that stays at one price (FR-3.2). */
  int bestRank(final int side) {
    return ladder(side).best();
  }

  void add(final int side, final int slot) {
    ladder(side).append(rankOf(side, slab.tick(slot)), slot);
    namePut(slot);
  }

  void remove(final int side, final int slot) {
    ladder(side).unlink(rankOf(side, slab.tick(slot)), slot);
    nameRemove(slab.participantId(slot), slab.clientOrderId(slot));
  }

  /** The order's quantities changed in place, so the level's totals follow (NFR-3.1). */
  void quantitiesChanged(
      final int side, final int slot, final long displayedDelta, final long remainingDelta) {
    ladder(side).adjust(rankOf(side, slab.tick(slot)), displayedDelta, remainingDelta);
  }

  /** A replenished tranche joins the back of the queue at its price (FR-5.4). */
  void requeued(
      final int side, final int slot, final long displayedDelta, final long remainingDelta) {
    ladder(side).requeue(rankOf(side, slab.tick(slot)), slot, displayedDelta, remainingDelta);
  }

  /** The slot resting under the name, or zero (FR-4.1). */
  int named(final int participantId, final long clientOrderId) {
    int at = slotOf(participantId, clientOrderId);
    while ((int) (names[(at << 1) + 1] >>> 32) != 0) {
      if (names[at << 1] == clientOrderId && (int) names[(at << 1) + 1] == participantId) {
        return (int) (names[(at << 1) + 1] >>> 32);
      }
      at = (at + 1) & nameMask;
    }
    return 0;
  }

  /** Every resting order for one participant appended to the caller's space (FR-4.7). */
  void of(final int participantId, final IntScratch into) {
    for (int at = 0; at <= nameMask; at++) {
      final long entry = names[(at << 1) + 1];
      if ((int) (entry >>> 32) != 0 && (int) entry == participantId) {
        into.add((int) (entry >>> 32));
      }
    }
  }

  /**
   * How much a taker could fill, summed over crossing levels only. With no self match id in play
   * the cached totals answer without touching an order; with one, the queues are walked, since the
   * exclusion is per order (FR-3.7).
   */
  long fillable(final int takerSide, final int limitRank, final long smpId) {
    final Ladder resting = ladder(takerSide ^ 1);
    long total = 0;
    for (int rank = resting.best(); rank <= limitRank; rank = resting.occupiedFrom(rank + 1)) {
      if (smpId == 0) {
        total += resting.remainingAt(rank);
      } else {
        for (int slot = resting.headAt(rank); slot != 0; slot = slab.next(slot)) {
          if (slab.smpId(slot) != smpId) {
            total += slab.remaining(slot);
          }
        }
      }
    }
    return total;
  }

  // Walks for the auction and the uncrossing ----------------------------------------------------

  int firstRank(final int side) {
    return ladder(side).best();
  }

  int rankAfter(final int side, final int rank) {
    return ladder(side).occupiedFrom(rank + 1);
  }

  int headAtRank(final int side, final int rank) {
    return ladder(side).headAt(rank);
  }

  long remainingAtRank(final int side, final int rank) {
    return ladder(side).remainingAt(rank);
  }

  // The name index -------------------------------------------------------------------------------

  private void allocateNames(final int capacity) {
    names = new long[capacity << 1];
    nameMask = capacity - 1;
  }

  private int slotOf(final int participantId, final long clientOrderId) {
    long hash = clientOrderId * 0x9E3779B97F4A7C15L ^ participantId * 0xC2B2AE3D27D4EB4FL;
    hash ^= hash >>> 32;
    return (int) hash & nameMask;
  }

  private void namePut(final int slot) {
    if ((nameCount + 1) * 2 > nameMask + 1) {
      grow();
    }
    final int participantId = slab.participantId(slot);
    final long clientOrderId = slab.clientOrderId(slot);
    int at = slotOf(participantId, clientOrderId);
    while ((int) (names[(at << 1) + 1] >>> 32) != 0) {
      at = (at + 1) & nameMask;
    }
    names[at << 1] = clientOrderId;
    names[(at << 1) + 1] = (participantId & 0xFFFF_FFFFL) | ((long) slot << 32);
    nameCount++;
  }

  private void nameRemove(final int participantId, final long clientOrderId) {
    int at = slotOf(participantId, clientOrderId);
    while (names[at << 1] != clientOrderId || (int) names[(at << 1) + 1] != participantId) {
      at = (at + 1) & nameMask;
    }
    nameCount--;
    // Backward shift rather than a tombstone: the table's occupancy is its contents, so the load
    // never quietly climbs and the steady state never rehashes (NFR-4.3).
    int hole = at;
    int probe = (hole + 1) & nameMask;
    while ((int) (names[(probe << 1) + 1] >>> 32) != 0) {
      final int home = slotOf((int) names[(probe << 1) + 1], names[probe << 1]);
      final boolean reachable = ((probe - home) & nameMask) >= ((probe - hole) & nameMask);
      if (reachable) {
        names[hole << 1] = names[probe << 1];
        names[(hole << 1) + 1] = names[(probe << 1) + 1];
        hole = probe;
      }
      probe = (probe + 1) & nameMask;
    }
    names[(hole << 1) + 1] = 0;
  }

  private void grow() {
    final long[] old = names;
    allocateNames((nameMask + 1) * 2);
    nameCount = 0;
    for (int at = 0; at < old.length; at += 2) {
      final int slot = (int) (old[at + 1] >>> 32);
      if (slot != 0) {
        nameCount++;
        int to = slotOf((int) old[at + 1], old[at]);
        while ((int) (names[(to << 1) + 1] >>> 32) != 0) {
          to = (to + 1) & nameMask;
        }
        names[to << 1] = old[at];
        names[(to << 1) + 1] = old[at + 1];
      }
    }
  }

  // Views for the tests, allocated freshly, never on the command path ---------------------------

  /** Every resting slot, in table order; the invariants sort and count it. */
  List<Integer> restingSlots() {
    final List<Integer> all = new ArrayList<>();
    for (int at = 0; at <= nameMask; at++) {
      final int slot = (int) (names[(at << 1) + 1] >>> 32);
      if (slot != 0) {
        all.add(slot);
      }
    }
    return all;
  }

  /** One side's occupied ticks, best price first, as the bitmap tells it. */
  List<Integer> occupiedTicks(final int side) {
    final List<Integer> ticks = new ArrayList<>();
    final Ladder ladder = ladder(side);
    for (int rank = ladder.best(); rank != Ladder.EMPTY; rank = ladder.occupiedFrom(rank + 1)) {
      ticks.add(tickOfRank(side, rank));
    }
    return ticks;
  }

  List<Integer> queueAt(final int side, final int tick) {
    final List<Integer> slots = new ArrayList<>();
    for (int slot = ladder(side).headAt(rankOf(side, tick)); slot != 0; slot = slab.next(slot)) {
      slots.add(slot);
    }
    return slots;
  }

  /** From the stored tail pointer backwards, so a stale tail or link cannot hide from the test. */
  List<Integer> queueReversedAt(final int side, final int tick) {
    final List<Integer> slots = new ArrayList<>();
    for (int slot = ladder(side).tailAt(rankOf(side, tick));
        slot != 0;
        slot = slab.previous(slot)) {
      slots.add(slot);
    }
    return slots;
  }

  long displayedTotalAt(final int side, final int tick) {
    return ladder(side).displayedAt(rankOf(side, tick));
  }

  long remainingTotalAt(final int side, final int tick) {
    return ladder(side).remainingAt(rankOf(side, tick));
  }

  boolean occupiedBit(final int side, final int tick) {
    return ladder(side).occupied(rankOf(side, tick));
  }

  int headSlotAt(final int side, final int tick) {
    return ladder(side).headAt(rankOf(side, tick));
  }

  int cachedBestRank(final int side) {
    return ladder(side).best();
  }

  int searchedBestRank(final int side) {
    return ladder(side).occupiedFrom(0);
  }

  int tickCount() {
    return maxRank + 1;
  }
}

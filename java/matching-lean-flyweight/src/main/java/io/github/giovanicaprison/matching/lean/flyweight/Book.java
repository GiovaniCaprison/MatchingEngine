package io.github.giovanicaprison.matching.lean.flyweight;

/**
 * The flyweight rung's book with nothing on it the lean remit does not need: the same flat ladder
 * per side in the same folded rank space, the same three level occupancy bitmap under the same
 * cached best, and the same interleaved open-addressing name index, so the comparison between the
 * two isolates the feature set at this layout and not a structural difference (P-16). What is
 * missing is the feature machinery: one cached total per level instead of two, since nothing here
 * hides quantity, and no fillable pre-check, no pro-rata snapshot, no re-queueing, because nothing
 * here replenishes. And like the rung it shadows, the steady state allocates nothing (NFR-4.3).
 */
final class Book {

  /** No occupied rank, above every reachable limit rank by construction. */
  static final int EMPTY = Integer.MAX_VALUE;

  private final Slab slab;
  private final long tickSize;
  private final long baseTick;
  private final int maxRank;

  /** Head in the low 32 bits, tail in the high 32, zero meaning nobody is at the price. */
  private final long[] bidQueues;

  private final long[] askQueues;

  /** The cached remaining total per level, the number the queue must always sum to (NFR-3.1). */
  private final long[] bidTotals;

  private final long[] askTotals;

  private final long[] bidBits0;
  private final long[] bidBits1;
  private final long[] bidBits2;
  private final long[] askBits0;
  private final long[] askBits1;
  private final long[] askBits2;

  private int bestBid = EMPTY;
  private int bestAsk = EMPTY;

  /** Client order id at {@code entry << 1}; participant in the low half beside it, slot above. */
  private long[] names;

  private int nameMask;
  private int nameCount;

  Book(final Slab slab, final long tickSize, final long baseTick, final int rankCount) {
    this.slab = slab;
    this.tickSize = tickSize;
    this.baseTick = baseTick;
    this.maxRank = rankCount - 1;
    bidQueues = new long[rankCount];
    askQueues = new long[rankCount];
    bidTotals = new long[rankCount];
    askTotals = new long[rankCount];
    bidBits0 = new long[words(rankCount)];
    bidBits1 = new long[words(bidBits0.length)];
    bidBits2 = new long[words(bidBits1.length)];
    askBits0 = new long[words(rankCount)];
    askBits1 = new long[words(askBits0.length)];
    askBits2 = new long[words(askBits1.length)];
    allocateNames(1 << 16);
  }

  private static int words(final int bits) {
    return (bits + 63) >>> 6;
  }

  long priceOfTick(final int tick) {
    return (tick + baseTick) * tickSize;
  }

  /** The tick of a price validation already admitted (P-5); never checked here (P-14). */
  int tickOfPrice(final long price) {
    return (int) (price / tickSize - baseTick);
  }

  /** A side's view of a tick: bids rank best-first by reversing, asks are already ascending. */
  int rankOf(final int side, final int tick) {
    return side == 0 ? maxRank - tick : tick;
  }

  /** Every rank a market order can reach, which is all of them and never {@link #EMPTY}. */
  int marketLimit() {
    return maxRank;
  }

  /** The order a taker reaches next: the front of the best crossing level (FR-3.1, FR-3.3). */
  int nextToTake(final int takerSide, final int limitRank) {
    if (takerSide == 0) {
      return bestAsk > limitRank ? 0 : (int) askQueues[bestAsk];
    }
    return bestBid > limitRank ? 0 : (int) bidQueues[bestBid];
  }

  void add(final int side, final int slot) {
    final int rank = rankOf(side, slab.tick(slot));
    final long[] queues = side == 0 ? bidQueues : askQueues;
    final long queue = queues[rank];
    final int tail = (int) (queue >>> 32);
    slab.link(slot, tail, 0);
    if (tail == 0) {
      queues[rank] = pack(slot, slot);
      set(side, rank);
      if (side == 0) {
        bestBid = Math.min(bestBid, rank);
      } else {
        bestAsk = Math.min(bestAsk, rank);
      }
    } else {
      slab.linkNext(tail, slot);
      queues[rank] = pack((int) queue, slot);
    }
    (side == 0 ? bidTotals : askTotals)[rank] += slab.remaining(slot);
    namePut(slot);
  }

  void remove(final int side, final int slot) {
    final int rank = rankOf(side, slab.tick(slot));
    final long[] queues = side == 0 ? bidQueues : askQueues;
    (side == 0 ? bidTotals : askTotals)[rank] -= slab.remaining(slot);
    final int previous = slab.previous(slot);
    final int next = slab.next(slot);
    final long queue = queues[rank];
    int head = (int) queue;
    int tail = (int) (queue >>> 32);
    if (previous == 0) {
      head = next;
    } else {
      slab.linkNext(previous, next);
    }
    if (next == 0) {
      tail = previous;
    } else {
      slab.linkPrevious(next, previous);
    }
    slab.link(slot, 0, 0);
    queues[rank] = pack(head, tail);
    if (head == 0) {
      // (NFR-3.2) An empty level does not survive: the bit clears and the best moves on, so the
      // summary never points at a price nobody is at.
      clear(side, rank);
      if (side == 0) {
        if (rank == bestBid) {
          bestBid = occupiedFrom(0, rank);
        }
      } else if (rank == bestAsk) {
        bestAsk = occupiedFrom(1, rank);
      }
    }
    nameRemove(slab.participantId(slot), slab.clientOrderId(slot));
  }

  /** The order's remaining quantity changed in place, so the level's total follows (NFR-3.1). */
  void quantityChanged(final int side, final int slot, final long delta) {
    (side == 0 ? bidTotals : askTotals)[rankOf(side, slab.tick(slot))] += delta;
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

  private static long pack(final int head, final int tail) {
    return ((long) tail << 32) | (head & 0xFFFF_FFFFL);
  }

  // The occupancy bitmap ---------------------------------------------------------------------

  private void set(final int side, final int rank) {
    final int word0 = rank >>> 6;
    final int word1 = word0 >>> 6;
    if (side == 0) {
      bidBits0[word0] |= 1L << rank;
      bidBits1[word1] |= 1L << word0;
      bidBits2[word1 >>> 6] |= 1L << word1;
    } else {
      askBits0[word0] |= 1L << rank;
      askBits1[word1] |= 1L << word0;
      askBits2[word1 >>> 6] |= 1L << word1;
    }
  }

  private void clear(final int side, final int rank) {
    final long[] bits0 = side == 0 ? bidBits0 : askBits0;
    final int word0 = rank >>> 6;
    if ((bits0[word0] &= ~(1L << rank)) != 0) {
      return;
    }
    final long[] bits1 = side == 0 ? bidBits1 : askBits1;
    final int word1 = word0 >>> 6;
    if ((bits1[word1] &= ~(1L << word0)) != 0) {
      return;
    }
    (side == 0 ? bidBits2 : askBits2)[word1 >>> 6] &= ~(1L << word1);
  }

  /** The lowest occupied rank at or above the argument, three trailing-zero counts away. */
  private int occupiedFrom(final int side, final int rank) {
    final long[] bits0 = side == 0 ? bidBits0 : askBits0;
    final long[] bits1 = side == 0 ? bidBits1 : askBits1;
    final long[] bits2 = side == 0 ? bidBits2 : askBits2;
    int word0 = rank >>> 6;
    if (word0 >= bits0.length) {
      return EMPTY;
    }
    final long low = bits0[word0] & (-1L << rank);
    if (low != 0) {
      return (word0 << 6) + Long.numberOfTrailingZeros(low);
    }
    int word1 = word0 >>> 6;
    long mid = above(bits1[word1], word0 & 63);
    if (mid == 0) {
      int word2 = word1 >>> 6;
      long high = above(bits2[word2], word1 & 63);
      while (high == 0) {
        word2++;
        if (word2 == bits2.length) {
          return EMPTY;
        }
        high = bits2[word2];
      }
      word1 = (word2 << 6) + Long.numberOfTrailingZeros(high);
      mid = bits1[word1];
    }
    word0 = (word1 << 6) + Long.numberOfTrailingZeros(mid);
    return (word0 << 6) + Long.numberOfTrailingZeros(bits0[word0]);
  }

  /** The bits of the word strictly above the given bit, safe at the top where a shift wraps. */
  private static long above(final long word, final int bit) {
    return bit == 63 ? 0 : word & (-1L << (bit + 1));
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
}

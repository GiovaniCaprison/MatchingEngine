package io.github.giovanicaprison.matching.flyweight;

/**
 * One side's price levels as a flat array indexed by rank, with an occupancy bitmap above it.
 *
 * <p>A rank is the side's own view of a tick: the ask ladder ranks ticks as they are and the bid
 * ladder ranks them reversed, so rank zero is the best price on either side and every walk, every
 * crossing test and every best-price search is the same arithmetic. The fold is the flat successor
 * of the pooled rung's negated tree key.
 *
 * <p>A level is two words: the queue's head and tail packed into one long, and its cached displayed
 * and remaining totals interleaved in another array so one line carries both (NFR-3.1). There is no
 * level object to acquire or release; an empty level is a zero word, which is what a freshly
 * allocated array already holds.
 *
 * <p>Best price discovery never scans the ladder. A three level bitmap says which ranks are
 * occupied, sixty four ranks to a word, sixty four words to a summary bit, and the lowest occupied
 * rank falls out of three trailing-zero counts. The bits change exactly when a queue becomes empty
 * or stops being empty, so the summary can never quietly disagree with the ladder (NFR-3.2), and
 * the invariants suite holds it to that.
 */
final class Ladder {

  /** No occupied rank, above every reachable limit rank by construction. */
  static final int EMPTY = Integer.MAX_VALUE;

  private final Slab slab;
  private final int ranks;

  /** Head in the low 32 bits, tail in the high 32, zero meaning nobody is at the price. */
  private final long[] queues;

  /** Displayed total at {@code rank << 1}, remaining total beside it (NFR-3.1). */
  private final long[] totals;

  private final long[] bits0;
  private final long[] bits1;
  private final long[] bits2;

  private int best = EMPTY;

  Ladder(final Slab slab, final int ranks) {
    this.slab = slab;
    this.ranks = ranks;
    queues = new long[ranks];
    totals = new long[ranks << 1];
    bits0 = new long[words(ranks)];
    bits1 = new long[words(bits0.length)];
    bits2 = new long[words(bits1.length)];
  }

  private static int words(final int bits) {
    return (bits + 63) >>> 6;
  }

  /** The best occupied rank, or {@link #EMPTY}, cached so the common question costs one read. */
  int best() {
    return best;
  }

  int headAt(final int rank) {
    return (int) queues[rank];
  }

  int tailAt(final int rank) {
    return (int) (queues[rank] >>> 32);
  }

  long displayedAt(final int rank) {
    return totals[rank << 1];
  }

  long remainingAt(final int rank) {
    return totals[(rank << 1) + 1];
  }

  /** The order's quantities changed in place, so the level's totals follow (NFR-3.1). */
  void adjust(final int rank, final long displayedDelta, final long remainingDelta) {
    totals[rank << 1] += displayedDelta;
    totals[(rank << 1) + 1] += remainingDelta;
  }

  /** Joins the back of the queue at the rank, opening the level if nobody was there. */
  void append(final int rank, final int slot) {
    final long queue = queues[rank];
    final int tail = (int) (queue >>> 32);
    slab.link(slot, tail, 0);
    if (tail == 0) {
      queues[rank] = pack(slot, slot);
      set(rank);
      best = Math.min(best, rank);
    } else {
      slab.linkNext(tail, slot);
      queues[rank] = pack((int) queue, slot);
    }
    adjust(rank, slab.displayed(slot), slab.remaining(slot));
  }

  /**
   * Detaches the slot from its queue completely (P-13), closing the level when it empties: an empty
   * level does not survive, so the bitmap and the best cache never point at a price nobody is at
   * (NFR-3.2).
   */
  void unlink(final int rank, final int slot) {
    adjust(rank, -slab.displayed(slot), -slab.remaining(slot));
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
      clear(rank);
      if (rank == best) {
        best = occupiedFrom(rank);
      }
    }
  }

  /** A replenished tranche joins the back of the queue at its price (FR-5.4). */
  void requeue(
      final int rank, final int slot, final long displayedDelta, final long remainingDelta) {
    final int previous = slab.previous(slot);
    final int next = slab.next(slot);
    final long queue = queues[rank];
    final int head = previous == 0 ? next : (int) queue;
    final int tail = (int) (queue >>> 32);
    if (slot != tail) {
      if (previous == 0) {
        slab.linkPrevious(next, 0);
      } else {
        slab.linkNext(previous, next);
        slab.linkPrevious(next, previous);
      }
      slab.link(slot, tail, 0);
      slab.linkNext(tail, slot);
      queues[rank] = pack(head, slot);
    }
    adjust(rank, displayedDelta, remainingDelta);
  }

  private static long pack(final int head, final int tail) {
    return ((long) tail << 32) | (head & 0xFFFF_FFFFL);
  }

  // The occupancy bitmap ---------------------------------------------------------------------

  private void set(final int rank) {
    final int word0 = rank >>> 6;
    final int word1 = word0 >>> 6;
    bits0[word0] |= 1L << rank;
    bits1[word1] |= 1L << word0;
    bits2[word1 >>> 6] |= 1L << word1;
  }

  private void clear(final int rank) {
    final int word0 = rank >>> 6;
    if ((bits0[word0] &= ~(1L << rank)) != 0) {
      return;
    }
    final int word1 = word0 >>> 6;
    if ((bits1[word1] &= ~(1L << word0)) != 0) {
      return;
    }
    bits2[word1 >>> 6] &= ~(1L << word1);
  }

  /**
   * The lowest occupied rank at or above the argument, or {@link #EMPTY}: one masked word per level
   * going up, one trailing-zero count per level coming down, never a scan of the ladder.
   */
  int occupiedFrom(final int rank) {
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

  /** The bitmap's own answer for one rank, for the invariants that hold it to the ladder. */
  boolean occupied(final int rank) {
    return (bits0[rank >>> 6] & (1L << rank)) != 0;
  }

  int rankCount() {
    return ranks;
  }
}

package io.github.giovanicaprison.matching.lean.flyweight;

import java.util.Arrays;

/**
 * Every order this twin holds, as slots in one preallocated array of longs, with nothing on a slot
 * a limit or market order does not need.
 *
 * <p>This is the object the comparison is about (P-16). The full rung's stride carries a trigger
 * price, a display size, a minimum quantity, a self match id and a displayed quantity, and every
 * one of them occupies the layout whether or not the flow uses it. Here they do not exist, so the
 * stride is eight longs and a whole order is one cache line, which is the honest measure of what
 * their existing costs at this layout.
 *
 * <p>Everything else is the full rung's slab: slot zero is the null link, the free list threads
 * through the queue links (P-13), nothing is validated (P-14), and a slot's state is a function of
 * its most recent {@link #init}.
 */
final class Slab {

  private static final int REMAINING = 0;
  private static final int EXECUTED = 1;
  private static final int ID = 2;

  /** Previous in the high 32 bits, next in the low 32, zero meaning end of chain. */
  private static final int LINKS = 3;

  private static final int TICK = 4;
  private static final int ARRIVAL = 5;
  private static final int CLIENT = 6;

  /** Participant in the low 32 bits, then side, pricing and time in force. */
  private static final int META = 7;

  private static final int STRIDE_SHIFT = 3;

  private long[] cells;
  private int capacity;
  private int freeHead;

  Slab(final int preallocated) {
    capacity = preallocated;
    cells = new long[capacity << STRIDE_SHIFT];
    thread(1);
  }

  private void thread(final int first) {
    for (int slot = capacity - 1; slot >= first; slot--) {
      cells[(slot << STRIDE_SHIFT) + LINKS] = freeHead & 0xFFFF_FFFFL;
      freeHead = slot;
    }
  }

  /**
   * Growth is paid on the way up to the high-water mark; the steady state after it allocates
   * nothing (NFR-4.3), and every slot index stays valid across it.
   */
  int acquire() {
    if (freeHead == 0) {
      final int first = capacity;
      capacity <<= 1;
      cells = Arrays.copyOf(cells, capacity << STRIDE_SHIFT);
      thread(first);
    }
    final int slot = freeHead;
    freeHead = next(slot);
    cells[(slot << STRIDE_SHIFT) + LINKS] = 0;
    return slot;
  }

  /** The slot must already be out of every chain (P-13); only the link word is rewritten. */
  void release(final int slot) {
    cells[(slot << STRIDE_SHIFT) + LINKS] = freeHead & 0xFFFF_FFFFL;
    freeHead = slot;
  }

  /** A fresh life for a slot: every field is written, nothing survives the last one. */
  void init(
      final int slot,
      final long id,
      final long clientOrderId,
      final int participantId,
      final int side,
      final int pricing,
      final int timeInForce,
      final int tick,
      final long quantity,
      final long arrival,
      final long executed) {
    final int at = slot << STRIDE_SHIFT;
    cells[at + REMAINING] = quantity;
    cells[at + EXECUTED] = executed;
    cells[at + ID] = id;
    cells[at + LINKS] = 0;
    cells[at + TICK] = tick;
    cells[at + ARRIVAL] = arrival;
    cells[at + CLIENT] = clientOrderId;
    cells[at + META] =
        (participantId & 0xFFFF_FFFFL)
            | ((long) side << 32)
            | ((long) pricing << 33)
            | ((long) timeInForce << 34);
  }

  /** What is left is what is shown. Without icebergs the two are the same number. */
  long remaining(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + REMAINING];
  }

  /** What has traded across the order's whole life, which a replace works its remainder from. */
  long executed(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + EXECUTED];
  }

  long id(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + ID];
  }

  int tick(final int slot) {
    return (int) cells[(slot << STRIDE_SHIFT) + TICK];
  }

  long arrival(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + ARRIVAL];
  }

  long clientOrderId(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + CLIENT];
  }

  int participantId(final int slot) {
    return (int) cells[(slot << STRIDE_SHIFT) + META];
  }

  int side(final int slot) {
    return (int) (cells[(slot << STRIDE_SHIFT) + META] >>> 32) & 1;
  }

  int pricing(final int slot) {
    return (int) (cells[(slot << STRIDE_SHIFT) + META] >>> 33) & 1;
  }

  int timeInForce(final int slot) {
    return (int) (cells[(slot << STRIDE_SHIFT) + META] >>> 34) & 3;
  }

  int next(final int slot) {
    return (int) cells[(slot << STRIDE_SHIFT) + LINKS];
  }

  int previous(final int slot) {
    return (int) (cells[(slot << STRIDE_SHIFT) + LINKS] >>> 32);
  }

  void link(final int slot, final int previous, final int next) {
    cells[(slot << STRIDE_SHIFT) + LINKS] = ((long) previous << 32) | (next & 0xFFFF_FFFFL);
  }

  void linkNext(final int slot, final int next) {
    final int at = (slot << STRIDE_SHIFT) + LINKS;
    cells[at] = (cells[at] & 0xFFFF_FFFF_0000_0000L) | (next & 0xFFFF_FFFFL);
  }

  void linkPrevious(final int slot, final int previous) {
    final int at = (slot << STRIDE_SHIFT) + LINKS;
    cells[at] = (cells[at] & 0xFFFF_FFFFL) | ((long) previous << 32);
  }

  void take(final int slot, final long quantity) {
    final int at = slot << STRIDE_SHIFT;
    cells[at + REMAINING] -= quantity;
    cells[at + EXECUTED] += quantity;
  }

  void rest(final int slot, final long arrivalSequence) {
    cells[(slot << STRIDE_SHIFT) + ARRIVAL] = arrivalSequence;
  }

  /** A replace that keeps queue position (FR-4.4) changes what is left and nothing else. */
  void reduceTo(final int slot, final long remainder) {
    cells[(slot << STRIDE_SHIFT) + REMAINING] = remainder;
  }
}

package io.github.giovanicaprison.matching.flyweight;

import java.util.Arrays;

/**
 * Every order the venue holds, as slots in one preallocated array of longs.
 *
 * <p>This is the rung's central move (P-17): an order stops being an object and becomes an int. A
 * field access is an array read at a computed offset, a queue link is an int in the same stride,
 * and the free list threads through the links the queues use, so a slot is always in exactly one
 * chain (P-13). Slot zero is reserved as the null link, which makes a freshly zeroed array already
 * empty and every "is there one" check a comparison against zero.
 *
 * <p>The stride is sixteen longs, 128 bytes, two cache lines, and the split between them is the
 * point. The first line carries what the take loop touches per fill: the quantities, the id the
 * execution reports, the tick, the links the unlink follows, the arrival a requeue rewrites and the
 * self match id compared per candidate (FR-3.7). The second carries identity and the entry-time
 * qualifiers, read once per command at most: the name a cancel arrives under, the participant, the
 * flags, the minimum quantity, the trigger price and the tranche size. Parallel arrays were the
 * alternative and lose here: a take reads six fields of one order, which is one line at this layout
 * and six lines split across six arrays.
 *
 * <p>Nothing here is validated (P-14) and nothing is cleared on release beyond the link word, so a
 * released slot still answers reads with its final values until it is reissued, which the auction
 * uncrossing relies on the way the pooled rung relied on the same property of its pool. A slot's
 * state is a function of its most recent {@link #init} (P-13).
 */
final class Slab {

  /** Hot line: what one execution touches, kept within one 64 byte stretch of the slot. */
  private static final int REMAINING = 0;

  private static final int DISPLAYED = 1;
  private static final int EXECUTED = 2;
  private static final int ID = 3;

  /** Previous in the high 32 bits, next in the low 32, zero meaning end of chain. */
  private static final int LINKS = 4;

  private static final int TICK = 5;
  private static final int ARRIVAL = 6;
  private static final int SMP = 7;

  /** Cold line: identity and entry-time qualifiers, touched once per command at most. */
  private static final int CLIENT = 8;

  /** Participant in the low 32 bits, then side, pricing, time in force and the post-only bit. */
  private static final int META = 9;

  private static final int MIN_QUANTITY = 10;
  private static final int TRIGGER = 11;
  private static final int DISPLAY_SIZE = 12;

  private static final int STRIDE_SHIFT = 4;
  private static final int STRIDE = 1 << STRIDE_SHIFT;

  private long[] cells;
  private int capacity;
  private int freeHead;

  Slab(final int preallocated) {
    capacity = preallocated;
    cells = new long[capacity << STRIDE_SHIFT];
    thread(1);
  }

  /** Chains every slot from {@code first} up into the free list, newest acquisitions lowest. */
  private void thread(final int first) {
    for (int slot = capacity - 1; slot >= first; slot--) {
      cells[(slot << STRIDE_SHIFT) + LINKS] = freeHead & 0xFFFF_FFFFL;
      freeHead = slot;
    }
  }

  /**
   * A slot to wear the next order. Growth doubles the slab and is paid on the way up to the
   * high-water mark of live orders, so the steady state after it allocates nothing (NFR-4.3), and
   * every slot index stays valid across it because an index is not an address.
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
      final boolean postOnly,
      final int tick,
      final long quantity,
      final long minQuantity,
      final long displayQuantity,
      final long triggerPrice,
      final long smpId,
      final long arrival,
      final long executed) {
    final int at = slot << STRIDE_SHIFT;
    cells[at + REMAINING] = quantity;
    cells[at + DISPLAYED] = displayQuantity == 0 ? quantity : Math.min(displayQuantity, quantity);
    cells[at + EXECUTED] = executed;
    cells[at + ID] = id;
    cells[at + LINKS] = 0;
    cells[at + TICK] = tick;
    cells[at + ARRIVAL] = arrival;
    cells[at + SMP] = smpId;
    cells[at + CLIENT] = clientOrderId;
    cells[at + META] =
        (participantId & 0xFFFF_FFFFL)
            | ((long) side << 32)
            | ((long) pricing << 33)
            | ((long) timeInForce << 34)
            | (postOnly ? 1L << 36 : 0);
    cells[at + MIN_QUANTITY] = minQuantity;
    cells[at + TRIGGER] = triggerPrice;
    cells[at + DISPLAY_SIZE] = displayQuantity;
  }

  long remaining(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + REMAINING];
  }

  /** What the feed has been told about, which is never the hidden part (FR-5.2). */
  long displayed(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + DISPLAYED];
  }

  /**
   * How much of this order has traded, over its whole life and across every replace, which is what
   * a replace's remainder is worked out from (FR-4.9).
   */
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

  long smpId(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + SMP];
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

  boolean postOnly(final int slot) {
    return (cells[(slot << STRIDE_SHIFT) + META] & (1L << 36)) != 0;
  }

  long minQuantity(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + MIN_QUANTITY];
  }

  long triggerPrice(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + TRIGGER];
  }

  /** The tranche size an iceberg shows at a time, which a replace has to preserve (FR-4.10). */
  long displaySize(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + DISPLAY_SIZE];
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

  /**
   * Takes quantity from the displayed part first, since that is all a taker can see. Returns
   * whether the displayed part is now empty while quantity remains, which is when a further tranche
   * is displayed and joins the back of its queue (FR-5.4).
   */
  boolean take(final int slot, final long quantity) {
    final int at = slot << STRIDE_SHIFT;
    cells[at + REMAINING] -= quantity;
    cells[at + EXECUTED] += quantity;
    cells[at + DISPLAYED] -= quantity;
    return cells[at + DISPLAYED] == 0 && cells[at + REMAINING] > 0;
  }

  /**
   * This slot is joining the queue at its price: what it shows and where it stands are settled now,
   * so an order that crossed on the way in queues behind everything that joined while it was
   * walking (FR-2.7), and a replenished tranche does the same (FR-5.4).
   */
  void rest(final int slot, final long arrivalSequence) {
    final int at = slot << STRIDE_SHIFT;
    final long size = cells[at + DISPLAY_SIZE];
    final long remaining = cells[at + REMAINING];
    cells[at + DISPLAYED] = size == 0 ? remaining : Math.min(size, remaining);
    cells[at + ARRIVAL] = arrivalSequence;
  }

  /** A replace that keeps queue position (FR-4.4) changes what is left and nothing else. */
  void reduceTo(final int slot, final long remainder) {
    final int at = slot << STRIDE_SHIFT;
    final long size = cells[at + DISPLAY_SIZE];
    cells[at + REMAINING] = remainder;
    cells[at + DISPLAYED] = size == 0 ? remainder : Math.min(size, remainder);
  }

  /**
   * A triggered stop becomes an ordinary order of its own pricing instruction (FR-6.3), in place,
   * because it is the same order and a fresh slot would carry no new information.
   */
  void triggered(final int slot, final long arrivalSequence) {
    final int at = slot << STRIDE_SHIFT;
    cells[at + TRIGGER] = 0;
    cells[at + ARRIVAL] = arrivalSequence;
  }

  /** A stop rests in the trigger book and is not book liquidity (FR-6.1). */
  boolean stop(final int slot) {
    return cells[(slot << STRIDE_SHIFT) + TRIGGER] != 0;
  }
}

package io.github.giovanicaprison.matching.flyweight;

import java.util.ArrayList;
import java.util.List;

/**
 * The stops that have not fired, chained through the slab's own links in arrival order.
 *
 * <p>A resting stop is not liquidity and is invisible to the book. It is a condition evaluated
 * against the last executed price, and on firing it becomes an ordinary order (FR-6.1, FR-6.3).
 * Still scanned, deliberately: firing order is arrival order among the reached, which the chain
 * already is because stops only ever join at the back, and the stop book stays small enough that an
 * index would cost more than the walk it saves (P-16).
 */
final class Triggers {

  private final Slab slab;
  private int head;
  private int tail;

  Triggers(final Slab slab) {
    this.slab = slab;
  }

  void add(final int slot) {
    slab.link(slot, tail, 0);
    if (tail == 0) {
      head = slot;
    } else {
      slab.linkNext(tail, slot);
    }
    tail = slot;
  }

  void remove(final int slot) {
    final int previous = slab.previous(slot);
    final int next = slab.next(slot);
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
  }

  int named(final int participantId, final long clientOrderId) {
    for (int slot = head; slot != 0; slot = slab.next(slot)) {
      if (slab.participantId(slot) == participantId && slab.clientOrderId(slot) == clientOrderId) {
        return slot;
      }
    }
    return 0;
  }

  void of(final int participantId, final IntScratch into) {
    for (int slot = head; slot != 0; slot = slab.next(slot)) {
      if (slab.participantId(slot) == participantId) {
        into.add(slot);
      }
    }
  }

  /**
   * Moves the stops the last executed price has reached into the caller's queue, earliest first,
   * removed as they go (FR-6.2).
   */
  void fire(final long lastExecutedPrice, final IntScratch into) {
    int slot = head;
    while (slot != 0) {
      final int following = slab.next(slot);
      final boolean reached =
          slab.side(slot) == 0
              ? lastExecutedPrice >= slab.triggerPrice(slot)
              : lastExecutedPrice <= slab.triggerPrice(slot);
      if (reached) {
        remove(slot);
        into.add(slot);
      }
      slot = following;
    }
  }

  /** Every waiting stop, allocated freshly, for the tests. */
  List<Integer> stops() {
    final List<Integer> all = new ArrayList<>();
    for (int slot = head; slot != 0; slot = slab.next(slot)) {
      all.add(slot);
    }
    return all;
  }
}

package io.github.giovanicaprison.matching.pooled;

import io.github.giovanicaprison.matching.protocol.Side;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The stops that have not fired.
 *
 * <p>A resting stop is not liquidity and is invisible to the book. It is a condition evaluated
 * against the last executed price, and on firing it becomes an ordinary order (FR-6.1, FR-6.3).
 *
 * <p>Still scanned, deliberately, so the step from the rung below stays about allocation. What
 * changed is the container: the stops chain through their own links in arrival order, so joining,
 * leaving and firing move pointers rather than list elements and nothing here allocates (NFR-4.3).
 */
final class Triggers {

  private Order head;
  private Order tail;

  void add(final Order stop) {
    stop.previous = tail;
    stop.next = null;
    if (tail == null) {
      head = stop;
    } else {
      tail.next = stop;
    }
    tail = stop;
  }

  void remove(final Order stop) {
    if (stop.previous == null) {
      head = stop.next;
    } else {
      stop.previous.next = stop.next;
    }
    if (stop.next == null) {
      tail = stop.previous;
    } else {
      stop.next.previous = stop.previous;
    }
    stop.previous = null;
    stop.next = null;
  }

  Order named(final int participantId, final long clientOrderId) {
    for (Order stop = head; stop != null; stop = stop.next) {
      if (stop.participantId() == participantId && stop.clientOrderId() == clientOrderId) {
        return stop;
      }
    }
    return null;
  }

  void of(final int participantId, final Scratch into) {
    for (Order stop = head; stop != null; stop = stop.next) {
      if (stop.participantId() == participantId) {
        into.add(stop);
      }
    }
  }

  /**
   * Moves the stops the last executed price has reached into the caller's queue, earliest first,
   * removed as they go (FR-6.2).
   *
   * <p>The chain is in arrival order because stops only ever join at the back, so walking it front
   * to back is already the order the rung below sorted into.
   */
  void fire(final long lastExecutedPrice, final ArrayDeque<Order> into) {
    Order stop = head;
    while (stop != null) {
      final Order following = stop.next;
      final boolean reached =
          stop.side() == Side.BUY
              ? lastExecutedPrice >= stop.triggerPrice()
              : lastExecutedPrice <= stop.triggerPrice();
      if (reached) {
        remove(stop);
        into.addLast(stop);
      }
      stop = following;
    }
  }

  /** Every waiting stop, allocated freshly, for the tests. */
  List<Order> stops() {
    final List<Order> all = new ArrayList<>();
    for (Order stop = head; stop != null; stop = stop.next) {
      all.add(stop);
    }
    return all;
  }
}

package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.protocol.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * The stops that have not fired.
 *
 * <p>A resting stop is not liquidity and is invisible to the book. It is a condition evaluated
 * against the last executed price, and on firing it becomes an ordinary order (FR-6.1, FR-6.3).
 *
 * <p>Another list, scanned. Every execution asks this structure whether anything fired, so on this
 * rung every execution costs a walk over every stop in the venue.
 */
final class Triggers {

  private final List<Order> stops = new ArrayList<>();

  void add(final Order stop) {
    stops.add(stop);
  }

  boolean remove(final Order stop) {
    return stops.remove(stop);
  }

  Order byId(final long id) {
    for (final Order stop : stops) {
      if (stop.id() == id) {
        return stop;
      }
    }
    return null;
  }

  List<Order> of(final int participantId) {
    final List<Order> found = new ArrayList<>();
    for (final Order stop : stops) {
      if (stop.participantId() == participantId) {
        found.add(stop);
      }
    }
    found.sort((left, right) -> Long.compare(left.arrival(), right.arrival()));
    return found;
  }

  /**
   * The stops the last executed price has reached, earliest first, removed as they go.
   *
   * <p>A buy stop is placed above the market and fires when the price rises to it; a sell stop is
   * below and fires when the price falls to it (FR-6.2).
   */
  List<Order> fire(final long lastExecutedPrice) {
    final List<Order> fired = new ArrayList<>();
    for (final Order stop : stops) {
      final boolean reached =
          stop.side() == Side.BUY
              ? lastExecutedPrice >= stop.triggerPrice()
              : lastExecutedPrice <= stop.triggerPrice();
      if (reached) {
        fired.add(stop);
      }
    }
    fired.sort((left, right) -> Long.compare(left.arrival(), right.arrival()));
    stops.removeAll(fired);
    return fired;
  }
}

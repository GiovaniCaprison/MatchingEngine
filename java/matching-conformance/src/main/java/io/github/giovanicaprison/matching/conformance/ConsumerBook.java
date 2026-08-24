package io.github.giovanicaprison.matching.conformance;

import io.github.giovanicaprison.matching.protocol.Side;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The book a consumer builds from the event stream, and nothing else.
 *
 * <p>This is the contract with the market data publisher above the engine, checked rather than
 * assumed. The events are supposed to be sufficient to rebuild the visible book, and the way to
 * find out is to rebuild it after every event and see whether it still makes sense.
 *
 * <p>Order within a price is kept, because the stream says it: orders rest in the order they
 * arrive, a reduction that keeps queue position emits no rest, and a replenished tranche appears at
 * the end. A book that agrees on quantities and disagrees on queue position would allocate
 * differently and pass a comparison that only added up.
 *
 * <p>Hidden quantity is not here and cannot be. A consumer is told the displayed part only, so this
 * is the visible book by construction, which is the only book the feed claims to describe.
 *
 * <p>Some orders are accepted and never rest: a stop waits outside the book, and an
 * immediate-or-cancel remainder leaves without ever having been there. Their removals name ids this
 * book never held, and that is correct rather than broken. So a removal for an id that is not
 * resting has three readings, and the difference is what is tracked: never accepted is a stream
 * nobody can follow, accepted and never rested is an order that was never visible, and rested and
 * already gone is a second removal for something the consumer has already let go.
 */
public final class ConsumerBook {

  /**
   * One resting order as the feed describes it.
   *
   * @param orderId the engine's id, which is how events name it
   * @param side which side it rests on
   * @param price where it rests
   * @param quantity how much of it is visible
   */
  public record Entry(long orderId, Side side, long price, long quantity) {

    Entry less(final long taken) {
      return new Entry(orderId, side, price, quantity - taken);
    }
  }

  /** Insertion ordered, so queue position at a price is part of what is compared. */
  private final Map<Long, Entry> entries = new LinkedHashMap<>();

  private final Set<Long> accepted = new HashSet<>();
  private final Set<Long> everRested = new HashSet<>();
  private final List<String> problems = new ArrayList<>();

  /** Every resting order the feed has described, in the order the feed described it. */
  public List<Entry> entries() {
    return List.copyOf(entries.values());
  }

  /**
   * What the stream said that a book cannot mean.
   *
   * <p>Empty is the only acceptable answer. Anything here is a feed a consumer cannot follow, which
   * is worse than a wrong number: the consumer's book and the engine's have parted company and
   * nothing downstream will notice until it matters.
   */
  public List<String> problems() {
    return List.copyOf(problems);
  }

  void accepted(final long orderId) {
    accepted.add(orderId);
  }

  void rested(final long orderId, final Side side, final long price, final long quantity) {
    if (!accepted.contains(orderId)) {
      problem(orderId + " rested without having been accepted");
      return;
    }
    if (entries.containsKey(orderId)) {
      problem(orderId + " rested while it was already resting");
      return;
    }
    if (quantity <= 0) {
      problem(orderId + " rested with nothing showing");
      return;
    }
    entries.put(orderId, new Entry(orderId, side, price, quantity));
    everRested.add(orderId);
  }

  /**
   * An execution names two orders, and whichever of them this book holds loses the quantity.
   *
   * <p>One rule for both regimes. In continuous trading the aggressor has not rested, so only the
   * resting side is here and only it is decremented. In an auction neither side aggressed and both
   * are here, so both are. A consumer that only ever followed the side called resting would hold a
   * filled order for the rest of the session.
   */
  void executed(
      final long aggressorOrderId,
      final long restingOrderId,
      final long price,
      final long quantity) {
    if (!entries.containsKey(restingOrderId) && !entries.containsKey(aggressorOrderId)) {
      problem(
          "executed between "
              + aggressorOrderId
              + " and "
              + restingOrderId
              + ", neither of them resting");
      return;
    }
    take(restingOrderId, price, quantity);
    take(aggressorOrderId, price, quantity);
  }

  private void take(final long orderId, final long price, final long quantity) {
    final Entry resting = entries.get(orderId);
    if (resting == null) {
      return;
    }
    // Never worse than the order's own limit, which holds at a resting price and at an uncrossing
    // one.
    final boolean worse =
        resting.side() == Side.BUY ? price > resting.price() : price < resting.price();
    if (worse) {
      problem(orderId + " executed at " + price + " having asked for " + resting.price());
      return;
    }
    if (quantity > resting.quantity()) {
      problem(orderId + " executed " + quantity + " with " + resting.quantity() + " showing");
      return;
    }
    final Entry left = resting.less(quantity);
    if (left.quantity() == 0) {
      // An order executed in full gets no removal. The consumer has seen it reach zero.
      entries.remove(orderId);
    } else {
      entries.put(orderId, left);
    }
  }

  void reduced(final long orderId, final long quantity) {
    final Entry resting = entries.get(orderId);
    if (resting == null) {
      problem(orderId + " was reduced while it was not resting");
      return;
    }
    if (quantity <= 0 || quantity > resting.quantity()) {
      problem(orderId + " was reduced from " + resting.quantity() + " to " + quantity);
      return;
    }
    entries.put(orderId, new Entry(orderId, resting.side(), resting.price(), quantity));
  }

  void removed(final long orderId, final long quantity) {
    final Entry resting = entries.remove(orderId);
    if (resting == null) {
      if (everRested.contains(orderId)) {
        // It rested, it left, and here it goes again. Twice removed is once too many: a consumer
        // that took this at face value would go looking for something it had already let go.
        problem(orderId + " removed after it had already left the book");
      } else if (!accepted.contains(orderId)) {
        problem(orderId + " removed without having been accepted");
      }
      // Otherwise it was accepted and never rested, which is a stop or an unfilled remainder
      // leaving.
      return;
    }
    if (quantity != resting.quantity()) {
      problem(orderId + " removed " + quantity + " with " + resting.quantity() + " showing");
    }
  }

  private void problem(final String description) {
    problems.add(description);
  }
}

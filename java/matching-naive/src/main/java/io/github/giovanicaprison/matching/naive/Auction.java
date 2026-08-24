package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.protocol.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * What an auction would do, and what it does.
 *
 * <p>The uncrossing price is the one that trades the most (FR-7.5). Ties go to the smallest
 * surplus, which is the side that would be left over, and then to whichever candidate is closest to
 * the reference price. Two prices that trade the same volume with the same surplus at the same
 * distance cannot both be chosen, so the lower one wins and the rule is written down rather than
 * left to a comparator nobody read.
 *
 * <p>Every distinct price in the book is a candidate and each one is priced by walking the whole
 * book, so this is quadratic in the number of orders. On this rung that is the honest cost of
 * asking.
 */
final class Auction {

  /**
   * The outcome of an uncrossing.
   *
   * @param price where it would trade, or zero when nothing crosses
   * @param quantity how much would trade there
   */
  record Uncrossing(long price, long quantity) {

    static final Uncrossing NOTHING = new Uncrossing(0, 0);

    boolean crosses() {
      return quantity > 0;
    }
  }

  private Auction() {}

  static Uncrossing uncrossing(final Book book, final long reference) {
    Uncrossing best = Uncrossing.NOTHING;
    long bestSurplus = Long.MAX_VALUE;
    for (final long candidate : candidates(book)) {
      final long demand = quantityWilling(book, Side.BUY, candidate);
      final long supply = quantityWilling(book, Side.SELL, candidate);
      final long tradeable = Math.min(demand, supply);
      if (tradeable == 0) {
        continue;
      }
      final long surplus = Math.abs(demand - supply);
      if (better(tradeable, surplus, candidate, best, bestSurplus, reference)) {
        best = new Uncrossing(candidate, tradeable);
        bestSurplus = surplus;
      }
    }
    return best;
  }

  private static boolean better(
      final long tradeable,
      final long surplus,
      final long candidate,
      final Uncrossing best,
      final long bestSurplus,
      final long reference) {
    if (tradeable != best.quantity()) {
      return tradeable > best.quantity();
    }
    if (surplus != bestSurplus) {
      return surplus < bestSurplus;
    }
    final long distance = Math.abs(candidate - reference);
    final long bestDistance = Math.abs(best.price() - reference);
    if (distance != bestDistance) {
      return distance < bestDistance;
    }
    return candidate < best.price();
  }

  /** Every price anyone has named, since the uncrossing price is always one of them. */
  private static List<Long> candidates(final Book book) {
    final List<Long> prices = new ArrayList<>();
    for (final Order order : book.orders()) {
      if (!prices.contains(order.price())) {
        prices.add(order.price());
      }
    }
    prices.sort(Long::compare);
    return prices;
  }

  /** How much one side would trade at a price: everyone who named that price or better. */
  private static long quantityWilling(final Book book, final Side side, final long price) {
    long total = 0;
    for (final Order order : book.orders()) {
      if (order.side() != side) {
        continue;
      }
      final boolean willing = side == Side.BUY ? order.price() >= price : order.price() <= price;
      if (willing) {
        total += order.remaining();
      }
    }
    return total;
  }
}

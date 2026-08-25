package io.github.giovanicaprison.matching.indexed;

import io.github.giovanicaprison.matching.protocol.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * What an auction would do, and what it does.
 *
 * <p>Every distinct price in the book is a candidate and each one is priced by walking the whole
 * book, so this is quadratic in the number of orders. On this rung that is the honest cost of
 * asking.
 *
 * <p>Hidden quantity is counted. An iceberg's concealed part is real liquidity and a real venue
 * lets it trade in an uncrossing, so leaving it out would find a price that leaves the book
 * crossed. What it does not buy is allocation priority, which is decided elsewhere.
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

  /**
   * One price, with everything the tie-break needs to know about it.
   *
   * @param price the candidate
   * @param tradeable how much would trade there
   * @param surplus how much would be left unfilled on whichever side is longer
   * @param pressure the side the surplus sits on, or null when the two balance
   */
  private record Candidate(long price, long tradeable, long surplus, Side pressure) {}

  private Auction() {}

  static Uncrossing uncrossing(final Book book, final long reference) {
    Candidate best = null;
    for (final long price : candidates(book)) {
      final Candidate candidate = priced(book, price);
      if (candidate.tradeable() == 0) {
        continue;
      }
      if (best == null || better(candidate, best, reference)) {
        best = candidate;
      }
    }
    return best == null ? Uncrossing.NOTHING : new Uncrossing(best.price(), best.tradeable());
  }

  private static Candidate priced(final Book book, final long price) {
    final long demand = quantityWilling(book, Side.BUY, price);
    final long supply = quantityWilling(book, Side.SELL, price);
    final Side pressure = demand == supply ? null : demand > supply ? Side.BUY : Side.SELL;
    return new Candidate(price, Math.min(demand, supply), Math.abs(demand - supply), pressure);
  }

  /**
   * Which of two candidates a venue would choose (FR-7.5).
   *
   * <p>Volume first, then the smaller surplus, and then the side the surplus is on. Unfilled demand
   * is buying pressure and settles high; unfilled supply settles low. That third rule is what makes
   * the price answer to the imbalance rather than to wherever the market happened to be beforehand,
   * and leaving it out lets a bidder who bid the market up be filled below the price they named.
   *
   * <p>Below that the reference price decides, which covers two cases: candidates that balance
   * exactly, and candidates whose equal surpluses sit on opposite sides. The last rule takes the
   * higher price, and it exists so that nothing is left to the order the candidates happened to be
   * walked in.
   */
  private static boolean better(final Candidate one, final Candidate than, final long reference) {
    if (one.tradeable() != than.tradeable()) {
      return one.tradeable() > than.tradeable();
    }
    if (one.surplus() != than.surplus()) {
      return one.surplus() < than.surplus();
    }
    if (one.pressure() == than.pressure() && one.pressure() == Side.BUY) {
      return one.price() > than.price();
    }
    if (one.pressure() == than.pressure() && one.pressure() == Side.SELL) {
      return one.price() < than.price();
    }
    final long distance = Math.abs(one.price() - reference);
    final long otherDistance = Math.abs(than.price() - reference);
    if (distance != otherDistance) {
      return distance < otherDistance;
    }
    return one.price() > than.price();
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
      if (order.side() == side && order.willingAt(price)) {
        total += order.remaining();
      }
    }
    return total;
  }
}

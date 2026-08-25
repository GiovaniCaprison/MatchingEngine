package io.github.giovanicaprison.matching.pooled;

import io.github.giovanicaprison.matching.protocol.Side;

/**
 * What an auction would do, and what it does.
 *
 * <p>Every distinct price in the book is a candidate and each one is priced by walking the levels
 * it could trade against, so the asking stays quadratic in the book's prices, which is this rung's
 * honest cost of the question. What the rung removes is the memory the rung below spent asking it:
 * the candidates are read off the level trees where they already sit sorted, the tally is a handful
 * of fields, and nothing is allocated however often the indicative is recomputed (NFR-4.3).
 *
 * <p>Hidden quantity is counted. An iceberg's concealed part is real liquidity and a real venue
 * lets it trade in an uncrossing, so leaving it out would find a price that leaves the book
 * crossed. What it does not buy is allocation priority, which is decided elsewhere.
 */
final class Auction {

  private final Book.Walk candidates = new Book.Walk();
  private final Book.Walk willing = new Book.Walk();

  private long price;
  private long quantity;

  private boolean have;
  private long bestPrice;
  private long bestTradeable;
  private long bestSurplus;
  private Side bestPressure;

  /** Where it would trade, or zero when nothing crosses. */
  long price() {
    return price;
  }

  /** How much would trade there. */
  long quantity() {
    return quantity;
  }

  boolean crosses() {
    return quantity > 0;
  }

  void uncross(final Book book, final long reference) {
    have = false;
    consider(book, Side.BUY, reference);
    consider(book, Side.SELL, reference);
    price = have ? bestPrice : 0;
    quantity = have ? bestTradeable : 0;
  }

  /**
   * Every price this side has named, since the uncrossing price is always one someone named. A
   * price both sides named is considered twice, which is harmless: the tie-break is a total
   * preference over distinct prices, so a candidate never displaces itself.
   */
  private void consider(final Book book, final Side side, final long reference) {
    book.walk(side, candidates);
    for (Book.Level level = candidates.next(); level != null; level = candidates.next()) {
      final long candidate = level.price();
      final long demand = quantityWilling(book, Side.BUY, candidate);
      final long supply = quantityWilling(book, Side.SELL, candidate);
      final long tradeable = Math.min(demand, supply);
      if (tradeable == 0) {
        continue;
      }
      final long surplus = Math.abs(demand - supply);
      final Side pressure = demand == supply ? null : demand > supply ? Side.BUY : Side.SELL;
      if (!have || better(candidate, tradeable, surplus, pressure, reference)) {
        have = true;
        bestPrice = candidate;
        bestTradeable = tradeable;
        bestSurplus = surplus;
        bestPressure = pressure;
      }
    }
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
  private boolean better(
      final long candidate,
      final long tradeable,
      final long surplus,
      final Side pressure,
      final long reference) {
    if (tradeable != bestTradeable) {
      return tradeable > bestTradeable;
    }
    if (surplus != bestSurplus) {
      return surplus < bestSurplus;
    }
    if (pressure == bestPressure && pressure == Side.BUY) {
      return candidate > bestPrice;
    }
    if (pressure == bestPressure && pressure == Side.SELL) {
      return candidate < bestPrice;
    }
    final long distance = Math.abs(candidate - reference);
    final long bestDistance = Math.abs(bestPrice - reference);
    if (distance != bestDistance) {
      return distance < bestDistance;
    }
    return candidate > bestPrice;
  }

  /**
   * How much one side would trade at a price: everyone who named that price or better. The side's
   * levels are walked best first, so the willing levels are a prefix and the walk stops at the
   * first level that is not.
   */
  private long quantityWilling(final Book book, final Side side, final long candidate) {
    long total = 0;
    book.walk(side, willing);
    for (Book.Level level = willing.next(); level != null; level = willing.next()) {
      if (side == Side.BUY ? level.price() < candidate : level.price() > candidate) {
        return total;
      }
      for (Order order = level.head(); order != null; order = order.next) {
        total += order.remaining();
      }
    }
    return total;
  }
}

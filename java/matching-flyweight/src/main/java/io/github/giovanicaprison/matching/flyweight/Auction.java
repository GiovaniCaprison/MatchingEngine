package io.github.giovanicaprison.matching.flyweight;

/**
 * What an auction would do, and what it does.
 *
 * <p>Every occupied price is a candidate and each one is priced against what both sides would trade
 * there, so the asking stays quadratic in the book's prices, which is the honest cost of the
 * question at every rung. What this rung removes is the touching: a side's willingness at a price
 * is a prefix sum over the ladder's cached remaining totals, read off the occupied ranks the bitmap
 * names, so no order is visited however often the indicative is recomputed (FR-7.7).
 *
 * <p>Hidden quantity is counted, because the totals carry remaining and an iceberg's concealed part
 * is real liquidity a venue lets trade in an uncrossing. What it does not buy is allocation
 * priority, which is decided elsewhere.
 */
final class Auction {

  private static final int NONE = -1;

  private long price;
  private long quantity;

  private boolean have;
  private long bestPrice;
  private long bestTradeable;
  private long bestSurplus;
  private int bestPressure;

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
    consider(book, 0, reference);
    consider(book, 1, reference);
    price = have ? bestPrice : 0;
    quantity = have ? bestTradeable : 0;
  }

  /**
   * Every price this side has named, since the uncrossing price is always one someone named. A
   * price both sides named is considered twice, which is harmless: the tie-break is a total
   * preference over distinct prices, so a candidate never displaces itself.
   */
  private void consider(final Book book, final int side, final long reference) {
    for (int rank = book.firstRank(side); rank != Ladder.EMPTY; rank = book.rankAfter(side, rank)) {
      final long candidate = book.priceOfRank(side, rank);
      final long demand = quantityWilling(book, 0, candidate);
      final long supply = quantityWilling(book, 1, candidate);
      final long tradeable = Math.min(demand, supply);
      if (tradeable == 0) {
        continue;
      }
      final long surplus = Math.abs(demand - supply);
      final int pressure = demand == supply ? NONE : demand > supply ? 0 : 1;
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
   * is buying pressure and settles high; unfilled supply settles low. Below that the reference
   * price decides, and the last rule takes the higher price, so nothing is left to the order the
   * candidates were walked in.
   */
  private boolean better(
      final long candidate,
      final long tradeable,
      final long surplus,
      final int pressure,
      final long reference) {
    if (tradeable != bestTradeable) {
      return tradeable > bestTradeable;
    }
    if (surplus != bestSurplus) {
      return surplus < bestSurplus;
    }
    if (pressure == bestPressure && pressure == 0) {
      return candidate > bestPrice;
    }
    if (pressure == bestPressure && pressure == 1) {
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
   * How much one side would trade at a price: everyone who named that price or better, which in
   * rank space is a prefix of the occupied ranks, summed from the cached totals (NFR-3.1).
   */
  private long quantityWilling(final Book book, final int side, final long candidate) {
    final int limit = book.willingLimitRank(side, candidate);
    long total = 0;
    for (int rank = book.firstRank(side); rank <= limit; rank = book.rankAfter(side, rank)) {
      total += book.remainingAtRank(side, rank);
    }
    return total;
  }
}

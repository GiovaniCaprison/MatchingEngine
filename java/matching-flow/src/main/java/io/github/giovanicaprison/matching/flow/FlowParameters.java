package io.github.giovanicaprison.matching.flow;

import io.github.giovanicaprison.matching.protocol.AllocationAlgorithm;

/**
 * What a flow is made of. Recorded verbatim in a run's manifest, since every finding is conditional
 * on it.
 *
 * @param seed the sequence the whole log is drawn from
 * @param commands how many commands the measured part of the log holds
 * @param restingOrders how many passive orders are placed first, to bring the book to size
 * @param instrument the definition the log opens with
 * @param composition what fraction of commands is what
 * @param placement where orders go and how large they are
 * @param auctionEvery commands between call phases, or zero to stay in continuous trading
 *     throughout
 */
public record FlowParameters(
    long seed,
    int commands,
    int restingOrders,
    Instrument instrument,
    Composition composition,
    Placement placement,
    int auctionEvery) {

  public FlowParameters {
    if (commands <= 0) {
      throw new IllegalArgumentException("a flow needs commands: " + commands);
    }
    if (restingOrders < 0) {
      throw new IllegalArgumentException("resting orders cannot be negative: " + restingOrders);
    }
    if (auctionEvery < 0) {
      throw new IllegalArgumentException("a period cannot be negative: " + auctionEvery);
    }
  }

  /**
   * How long a call phase lasts, which is a tenth of the gap between them.
   *
   * <p>Short on purpose. Nothing matches during a call phase, so a long one is a book that only
   * grows, and the uncrossing at the end of it stops resembling anything a venue sees.
   */
  public int callPhaseLength() {
    return Math.max(1, auctionEvery / 10);
  }

  /**
   * The instrument the log configures.
   *
   * @param tickSize price granularity
   * @param lotSize quantity granularity
   * @param minPrice lowest acceptable price
   * @param maxPrice highest acceptable price
   * @param priceScale implied decimal places
   * @param bandWidth half width of the dynamic band
   * @param openingReference the price the flow is centred on
   * @param allocation how a price level is shared
   */
  public record Instrument(
      long tickSize,
      long lotSize,
      long minPrice,
      long maxPrice,
      int priceScale,
      long bandWidth,
      long openingReference,
      AllocationAlgorithm allocation) {

    public static Instrument standard() {
      return new Instrument(5, 1, 1, 1_000_000, 4, 500, 100_000, AllocationAlgorithm.PRICE_TIME);
    }
  }

  /**
   * The mix, as fractions of the commands generated.
   *
   * <p>The first five are fractions of the commands generated, and passive order entry takes what
   * they leave. The qualifiers are fractions of the orders they can apply to: iceberg, stop,
   * post-only and self match of every order entered, and minimum quantity, immediate-or-cancel and
   * fill-or-kill of the orders that cross, since a resting order carrying one of those is either
   * refused or flow nobody sends.
   *
   * @param aggressive limit orders priced to cross
   * @param market orders carrying no price, which cross whatever is there
   * @param cancel commands that cancel an order placed earlier
   * @param replace commands that replace one
   * @param massCancel commands that cancel a whole participant
   * @param iceberg orders displaying less than their quantity
   * @param stop orders resting in the trigger book
   * @param immediateOrCancel orders whose remainder leaves
   * @param fillOrKill orders that execute whole or not at all
   * @param postOnly orders refused rather than allowed to take
   * @param minimumQuantity orders carrying a minimum execution quantity
   * @param selfMatch orders carrying a self match id
   */
  public record Composition(
      double aggressive,
      double market,
      double cancel,
      double replace,
      double massCancel,
      double iceberg,
      double stop,
      double immediateOrCancel,
      double fillOrKill,
      double postOnly,
      double minimumQuantity,
      double selfMatch) {

    /**
     * Every feature present, in the shape of a real book.
     *
     * <p>The first four are measured. AAPL on 30 January 2020, 09:30 to 11:08, from Nasdaq's public
     * TotalView-ITCH session: of 626,538 commands, 42.5% were deletes and 9.0% were replaces or
     * partial cancels. `matching-calibration` is the tool and the numbers reproduce.
     *
     * <p>The crossing share is fitted rather than read, because a market data feed cannot show it:
     * an order that filled completely never rested, so it never appears. What the feed does show is
     * that only 5.1% of posted shares ever executed, and these rates put the generator near that.
     *
     * <p>The qualifiers are chosen. None of iceberg, stop, post-only, minimum quantity or self
     * match is a field in a feed, so nothing observable constrains them. The one hint the session
     * gives is that 28% of executed shares were hidden, which is why the iceberg rate is not lower.
     */
    public static Composition standard() {
      return new Composition(
          0.020, 0.005, 0.425, 0.090, 0.0002, 0.06, 0.02, 0.06, 0.005, 0.08, 0.02, 0.10);
    }

    /**
     * Limit and market orders only.
     *
     * <p>The other half of the measurement of what a feature costs when nobody uses it. This
     * composition against the same engine gives the cost of a feature being available; the same
     * composition against the engine that has only these gives the cost of it existing (P-16).
     */
    public static Composition limitAndMarketOnly() {
      return new Composition(0.020, 0.005, 0.425, 0.090, 0, 0, 0, 0, 0, 0, 0, 0);
    }
  }

  /**
   * Where orders land and how large they are.
   *
   * <p>Both measured from the same session. Half of AAPL's orders were placed within six ticks of
   * the touch and nine tenths within eighty nine, so the draw is biased hard toward the touch and
   * the tail is cut where the instrument's band ends. And nine tenths of orders were a single round
   * lot, with the ninety ninth percentile at three, so a uniform draw over forty lots was around
   * twenty times too large and the wrong shape besides.
   *
   * @param depthTicks how far from the reference price an order can be placed
   * @param maximumLots the largest order the tail reaches, in lots
   *     <p>The participant count is not measured either, since a feed carries no firm identity, but
   *     it has to be of the right order. A mass cancel removes everything one participant has, so
   *     eight participants make every one of them take out an eighth of the book. Hundreds of firms
   *     quote a liquid stock, and at fifty the command stays the large one P-9 says it is without
   *     dominating a run on its own.
   * @param participants how many participants the flow comes from
   */
  public record Placement(int depthTicks, int maximumLots, int participants) {

    public static Placement standard() {
      return new Placement(60, 40, 50);
    }
  }

  public static FlowParameters standard(final long seed, final int commands) {
    return new FlowParameters(
        seed,
        commands,
        5_000,
        Instrument.standard(),
        Composition.standard(),
        Placement.standard(),
        0);
  }

  /**
   * Continuous trading interrupted by call phases, which is what a session actually looks like.
   *
   * <p>Far more auctions than a day holds, because this is for finding out whether the auction path
   * survives contact with the other features rather than for measuring anything.
   */
  public static FlowParameters withAuctions(
      final long seed, final int commands, final int restingOrders) {
    return new FlowParameters(
        seed,
        commands,
        restingOrders,
        Instrument.standard(),
        Composition.standard(),
        Placement.standard(),
        commands / 20 + 1);
  }
}

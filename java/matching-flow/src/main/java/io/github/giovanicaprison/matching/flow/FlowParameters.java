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
 */
public record FlowParameters(
    long seed,
    int commands,
    int restingOrders,
    Instrument instrument,
    Composition composition,
    Placement placement) {

  public FlowParameters {
    if (commands <= 0) {
      throw new IllegalArgumentException("a flow needs commands: " + commands);
    }
    if (restingOrders < 0) {
      throw new IllegalArgumentException("resting orders cannot be negative: " + restingOrders);
    }
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

    /** Every feature present, at rates loosely in the shape of a liquid equity book. */
    public static Composition standard() {
      return new Composition(
          0.15, 0.05, 0.35, 0.05, 0.0005, 0.04, 0.02, 0.06, 0.005, 0.08, 0.02, 0.10);
    }

    /**
     * Limit and market orders only.
     *
     * <p>The other half of the measurement of what a feature costs when nobody uses it. This
     * composition against the same engine gives the cost of a feature being available; the same
     * composition against the engine that has only these gives the cost of it existing (P-16).
     */
    public static Composition limitAndMarketOnly() {
      return new Composition(0.15, 0.05, 0.35, 0.05, 0, 0, 0, 0, 0, 0, 0, 0);
    }
  }

  /**
   * Where orders land.
   *
   * @param depthTicks how far from the reference price an order can be placed
   * @param maximumLots the largest order, in lots
   * @param participants how many participants the flow comes from
   */
  public record Placement(int depthTicks, int maximumLots, int participants) {

    public static Placement standard() {
      return new Placement(20, 40, 8);
    }
  }

  public static FlowParameters standard(final long seed, final int commands) {
    return new FlowParameters(
        seed, commands, 5_000, Instrument.standard(), Composition.standard(), Placement.standard());
  }
}

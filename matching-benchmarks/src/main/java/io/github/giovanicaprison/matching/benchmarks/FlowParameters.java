package io.github.giovanicaprison.matching.benchmarks;

/**
 * The shape of a generated flow. Every reported number has to name these, because a latency figure
 * without the flow that produced it is not comparable to anything (NFR-5.5).
 *
 * @param seed makes a run reproducible
 * @param midPrice the price the flow is centred on, scaled
 * @param tick the price granularity
 * @param halfSpreadTicks how far off the mid the touch sits on each side. Without it a passive buy
 *     and a passive sell can both land on the mid and trade with each other, so a flow meant to be
 *     passive is not
 * @param maxTicksFromTouch how far out an order may be placed
 * @param placementDecay chance of stopping at each tick outward, so most orders land near the touch
 * @param minQuantity smallest order quantity
 * @param maxQuantity largest order quantity
 */
public record FlowParameters(
    long seed,
    long midPrice,
    long tick,
    int halfSpreadTicks,
    int maxTicksFromTouch,
    double placementDecay,
    long minQuantity,
    long maxQuantity) {

  public static FlowParameters standard(final long seed) {
    return new FlowParameters(seed, 100_000L, 5L, 1, 40, 0.35, 1L, 50L);
  }

  public String describe() {
    return "seed=%d mid=%d tick=%d halfSpread=%d ticksOut=%d decay=%.2f qty=%d..%d"
        .formatted(
            seed,
            midPrice,
            tick,
            halfSpreadTicks,
            maxTicksFromTouch,
            placementDecay,
            minQuantity,
            maxQuantity);
  }
}

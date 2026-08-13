package com.imc.me.util;

import com.imc.me.domain.OrderSide;

/**
 * The market-order price sentinels, and the coupling they create written down in one place.
 *
 * <p>A MARKET order has no price, but the matching walk's stopping condition is "do the prices
 * cross". Giving a market order an extreme price makes it cross every level until liquidity runs out,
 * so the walk needs <b>no special case for market orders at all</b> — the type disappears from the
 * hot loop entirely, which is what lets phase 2 stay type-agnostic (OOD-8).
 *
 * <p><b>The hazard this class exists to document.</b> A sentinel is a real value in a {@code long}
 * field, so it flows everywhere a price flows. In particular {@code TreeMapBookSide.remove} finds an
 * order's level with {@code levels.get(order.price())} — with a sentinel key that lookup is
 * meaningless. It is harmless <i>only</i> because a MARKET order never rests (FR-2.2), which the
 * remainder switch now guarantees rather than merely intending. That is a coupling between two
 * distant pieces of code, and undocumented it becomes a corruption bug the day stop orders arrive
 * (a stop order is a resting order that carries a trigger price — exactly the combination this
 * assumption forbids).
 *
 * <p>Sentinels are assigned at the validation boundary, after the client's price has been checked
 * (OOD-5), so they can safely take values a client could never submit.
 */
public final class Prices {

  /**
   * A buy price that crosses every ask. Nothing can be priced above it, so {@code buyPrice >=
   * bestAsk} always holds while asks exist.
   */
  public static final long MARKET_BUY = Long.MAX_VALUE;

  /**
   * A sell price that crosses every bid. Zero rather than {@link Long#MIN_VALUE} because real prices
   * are strictly positive (VR-2.1), so zero is unmistakably not a client price, and it keeps every
   * price in the system non-negative — which matters when these become array indices into a price
   * ladder (OOD-18).
   */
  public static final long MARKET_SELL = 0L;

  private Prices() {}

  /** The sentinel a market order on this side must carry. */
  public static long marketPrice(final OrderSide side) {
    return side == OrderSide.BUY ? MARKET_BUY : MARKET_SELL;
  }

  /** Whether a price is a market sentinel rather than a real limit price. */
  public static boolean isMarketSentinel(final long price) {
    return price == MARKET_BUY || price == MARKET_SELL;
  }
}

package com.imc.me.util;

import com.imc.me.domain.OrderSide;

/**
 * The market-order price sentinels, and the coupling they create, written down in one place.
 *
 * <p>A market order has no price, but the walk's stopping condition is whether the prices cross.
 * Giving it an extreme price makes it cross every level until liquidity runs out, so the walk needs
 * no special case for market orders and the type disappears from the hot loop (OOD-8).
 *
 * <p>The hazard worth documenting is that a sentinel is a real value in a {@code long} field, so it
 * flows everywhere a price flows. {@code TreeMapBookSide.remove} finds an order's level with {@code
 * levels.get(order.price())}, and with a sentinel key that lookup is meaningless. It is harmless
 * only because a market order never rests (FR-2.2), which the remainder switch guarantees rather
 * than merely intends. Undocumented, that becomes a corruption bug the day stop orders arrive,
 * since a stop order is a resting order carrying a trigger price.
 *
 * <p>Sentinels are assigned at the validation boundary after the client's price has been checked
 * (OOD-5), so they can safely take values a client could never submit.
 */
public final class Prices {

  /**
   * A buy price that crosses every ask. Nothing can be priced above it, so {@code buyPrice >=
   * bestAsk} always holds while asks exist.
   */
  public static final long MARKET_BUY = Long.MAX_VALUE;

  /**
   * A sell price that crosses every bid. Zero rather than {@link Long#MIN_VALUE} because real
   * prices are strictly positive (VR-2.1), so zero is unmistakably not a client price, and it keeps
   * every price non-negative, which matters when these become indices into a price ladder (OOD-18).
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

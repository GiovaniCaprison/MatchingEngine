package com.imc.me.event.dto;

import com.imc.me.domain.OrderSide;

/**
 * The best price on one side and the quantity aggregated at it (FR-5.1).
 *
 * <p>A {@code present} flag rather than a null or a sentinel price, so an empty side is a value a
 * caller has to look at rather than a zero it can mistake for a real price (FR-5.2, OOD-6).
 */
public record TopOfBook(OrderSide side, boolean present, long price, long qty) {
  public static TopOfBook empty(OrderSide side) {
    return new TopOfBook(side, false, 0L, 0L);
  }

  public static TopOfBook of(OrderSide side, long price, long qty) {
    return new TopOfBook(side, true, price, qty);
  }

  public boolean isEmpty() {
    return !present;
  }
}

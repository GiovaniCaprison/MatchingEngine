package com.imc.me.event.dto;

import com.imc.me.domain.OrderSide;
import com.imc.me.util.Seq;

/**
 * A depth snapshot for one side, best price first.
 *
 * <p>Carries a {@link Seq} because a record accessor is a public method, so API-11.1 rules out
 * {@code List} here however carefully the constructor copies (OOD-9).
 */
public record Depth(OrderSide side, Seq<Level> levels) {

  /** One aggregated price level: the price, and the sum of remaining qty resting at it. */
  public record Level(long price, long qty) {}

  public static Depth empty(final OrderSide side) {
    return new Depth(side, Seq.empty());
  }
}

package com.imc.me.event.dto;

import com.imc.me.domain.OrderSide;
import com.imc.me.util.Seq;

/**
 * A depth snapshot for one side, best price first.
 *
 * <p>Carries a {@link Seq} rather than a {@code List} because API-11.1 forbids a public method
 * returning {@code List} and a record accessor is a public method — no amount of {@code List.copyOf}
 * in the constructor can satisfy it, and copying defensively allocates twice to buy a guarantee the
 * type can just state (OOD-9).
 */
public record Depth(OrderSide side, Seq<Level> levels) {

  /** One aggregated price level: the price, and the sum of remaining qty resting at it. */
  public record Level(long price, long qty) {}

  public static Depth empty(final OrderSide side) {
    return new Depth(side, Seq.empty());
  }
}

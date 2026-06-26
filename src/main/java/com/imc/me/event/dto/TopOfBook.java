package com.imc.me.event.dto;

import com.imc.me.domain.OrderSide;

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

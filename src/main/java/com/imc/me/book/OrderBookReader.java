package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.dto.OrderStatus;
import com.imc.me.event.dto.TopOfBook;

public sealed interface OrderBookReader permits OrderBook {
  TopOfBook topOfBook(final OrderSide side);

  Depth depth(final OrderSide side);

  OrderStatus orderStatus(final long orderId);
}

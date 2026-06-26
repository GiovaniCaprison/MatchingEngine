package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.dto.OrderStatus;
import com.imc.me.event.dto.TopOfBook;

public sealed interface OrderBookReader permits OrderBook {
  TopOfBook topOfBook(OrderSide side);

  Depth depth(OrderSide side);

  OrderStatus orderStatus(long orderId);
}

package com.imc.me.book;

import com.imc.me.domain.OrderSide;
import com.imc.me.event.dto.Depth;
import com.imc.me.event.dto.OrderStatus;
import com.imc.me.event.dto.TopOfBook;

public sealed interface OrderBookReader permits OrderBook {
  TopOfBook topOfBook(final OrderSide side);

  /**
   * A depth snapshot for one side, best price first, capped at {@code maxLevels} (OOD-10).
   *
   * <p>The cap is a required argument rather than a defaulted one so that a caller cannot ask for
   * "everything" by accident. See {@link BookSide#depth} for why unbounded is a denial of service.
   */
  Depth depth(final OrderSide side, final int maxLevels);

  OrderStatus orderStatus(final long orderId);
}

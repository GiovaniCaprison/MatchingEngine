package com.imc.me.book;

import com.imc.me.domain.Order;
import com.imc.me.event.result.AmendResult;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.SubmitResult;

public sealed interface OrderBookWriter permits OrderBook {
  SubmitResult submit(Order order);

  AmendResult amend(long orderId);

  CancelResult cancel(long orderId);
}

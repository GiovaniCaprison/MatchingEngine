package com.imc.me.book;

import com.imc.me.event.result.AmendResult;
import com.imc.me.event.result.CancelResult;
import com.imc.me.event.result.SubmitResult;

public sealed interface OrderBookWriter permits OrderBook {
  SubmitResult submit(final Order order);

  AmendResult amend(final long orderId);

  CancelResult cancel(final long orderId);
}

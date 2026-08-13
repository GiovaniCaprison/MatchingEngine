package com.imc.me.event.result;

import com.imc.me.domain.Trade;
import com.imc.me.util.Seq;

/** The order was found resting and removed, along with whatever it had executed before that. */
public record Cancelled(long orderId, Seq<Trade> fillsBeforeCancellation) implements CancelResult {

  /** Cancelled without ever executing. */
  public static Cancelled unfilled(final long orderId) {
    return new Cancelled(orderId, Seq.empty());
  }
}

package com.imc.me.event.result;

import com.imc.me.domain.Trade;
import com.imc.me.util.Seq;

/** The order was accepted, along with whatever it executed on entry. */
public record Accepted(long orderId, Seq<Trade> fills) implements SubmitResult, AmendResult {

  /** Accepted with no executions — it crossed nothing and is now resting. */
  public static Accepted resting(final long orderId) {
    return new Accepted(orderId, Seq.empty());
  }
}
